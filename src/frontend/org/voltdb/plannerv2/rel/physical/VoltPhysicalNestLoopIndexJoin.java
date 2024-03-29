/* This file is part of VoltDB.
 * Copyright (C) 2008-2019 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.plannerv2.rel.physical;

import java.util.Set;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.voltdb.catalog.Index;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.TupleValueExpression;
import org.voltdb.planner.AccessPath;
import org.voltdb.plannerv2.converter.RexConverter;
import org.voltdb.plannerv2.guards.PlannerFallbackException;
import org.voltdb.plannerv2.rel.util.PlanCostUtil;
import org.voltdb.plannodes.AbstractJoinPlanNode;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.IndexScanPlanNode;
import org.voltdb.plannodes.NestLoopIndexPlanNode;
import org.voltdb.plannodes.NodeSchema;
import org.voltdb.types.JoinType;

import com.google.common.collect.ImmutableList;
import com.google_voltpatches.common.base.Preconditions;

public class VoltPhysicalNestLoopIndexJoin extends VoltPhysicalJoin {

    // Needed for cost estimates
    private final Index m_innerIndex;
    private final AccessPath m_accessPath;

    public VoltPhysicalNestLoopIndexJoin(
            RelOptCluster cluster, RelTraitSet traitSet, RelNode left, RelNode right, RexNode condition,
            Set<CorrelationId> variablesSet, JoinRelType joinType, boolean semiJoinDone,
            ImmutableList<RelDataTypeField> systemFieldList,
            Index index, AccessPath accessPath) {
        this(cluster, traitSet, left, right, condition, variablesSet, joinType,
                semiJoinDone, systemFieldList, index, accessPath, null, null);
    }

    private VoltPhysicalNestLoopIndexJoin(
            RelOptCluster cluster, RelTraitSet traitSet,
            RelNode left, RelNode right, RexNode condition, Set<CorrelationId> variablesSet, JoinRelType joinType,
            boolean semiJoinDone, ImmutableList<RelDataTypeField> systemFieldList,
            Index index, AccessPath accessPath,
            RexNode offset, RexNode limit) {
        super(cluster, traitSet, left, right, condition, variablesSet, joinType,
                semiJoinDone, systemFieldList, offset, limit);
        Preconditions.checkNotNull(index, "Inner index is null");
        Preconditions.checkNotNull(accessPath, "Inner access path is null");
        m_innerIndex = index;
        m_accessPath = accessPath;
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
        super.explainTerms(pw);
        pw.item("innerIndex", m_innerIndex.getTypeName());
        return pw;
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        double rowCount = estimateRowCount(mq);
        double outerRowCount = getInput(0).estimateRowCount(mq);
        double innerRowCount = getInput(1).estimateRowCount(mq);
        double innerCpu = PlanCostUtil.computeIndexCost(m_innerIndex, m_accessPath,
                getTraitSet().getTrait(RelCollationTraitDef.INSTANCE),  innerRowCount);
        return planner.getCostFactory().makeCost(rowCount, outerRowCount * innerCpu, 0.);
    }

    @Override
    public AbstractPlanNode toPlanNode() {
        final NestLoopIndexPlanNode nlipn = new NestLoopIndexPlanNode();
        nlipn.setJoinType(JoinType.INNER);
        // Set children
        nlipn.addAndLinkChild(inputRelNodeToPlanNode(this, 0));
        // Set inner node table index to "1" prior to its conversion to AbstractPlanNode
        // The table index will be propagated to the inlined index scan's SkippNullPredicate.
        assert getInput(1) instanceof VoltPhysicalTableIndexScan;
        ((VoltPhysicalTableIndexScan) getInput(1)).setTableIdx(1);
        final AbstractPlanNode innerNode = inputRelNodeToPlanNode(this, 1);
        assert(innerNode instanceof IndexScanPlanNode);
        IndexScanPlanNode innerIndexScan = (IndexScanPlanNode) innerNode;
        nlipn.addInlinePlanNode(innerIndexScan);

        // We don't need to set the join predicate explicitly here because it will be
        // an index and/or filter expressions for the inline index scan
        // but we need to adjust the index scan's predicate - all TVE expressions there belong to the scan node
        // and their indexes have to be set to 1 because its an inner table
        // All other index scan expressions are part of a join expressions and should already have
        // the correct TVE index set
        final AbstractExpression postPredicate = innerIndexScan.getPredicate();
        if (postPredicate != null) {
            postPredicate.findAllSubexpressionsOfClass(TupleValueExpression.class)
                    .forEach(expr -> ((TupleValueExpression) expr).setTableIndex(1));
        }
        // Inline LIMIT / OFFSET
        addLimitOffset(nlipn);
        // Set output schema
        setOutputSchema(nlipn);
        return nlipn;
    }

    @Override
    protected void setOutputSchema(AbstractJoinPlanNode node) {
        Preconditions.checkNotNull(node, "Plan node is null");
        // An inner node has to be an index scan
        // Since it's going to be inlined and NLIJ executor will be iterating directly over
        // its persistent table all the expression references must be resolved
        // in context of the persistent table
        assert(getInput(1) instanceof VoltPhysicalTableIndexScan);
        VoltPhysicalTableIndexScan innerIndexScan = (VoltPhysicalTableIndexScan) getInput(1);
        RexProgram innerProgram = innerIndexScan.getProgram();
        NodeSchema innerSchema = RexConverter.convertToVoltDBNodeSchema(innerProgram, 1);
        // Join with the outer schema.
        RelDataType outerRowType = getInput(0).getRowType();
        NodeSchema outerSchema = RexConverter.convertToVoltDBNodeSchema(outerRowType, 0);
        final NodeSchema joinSchema = outerSchema.join(innerSchema);
        node.setOutputSchemaPreInlineAgg(joinSchema);
        node.setOutputSchema(joinSchema);
        node.setHaveSignificantOutputSchema(true);
    }

    @Override
    public Join copy(
            RelTraitSet traitSet, RexNode conditionExpr, RelNode left,
            RelNode right, JoinRelType joinType, boolean semiJoinDone) {
        return new VoltPhysicalNestLoopIndexJoin(getCluster(),
                getTraitSet(), left, right, conditionExpr,
                variablesSet, joinType, semiJoinDone, ImmutableList.copyOf(getSystemFieldList()),
                m_innerIndex, m_accessPath);
    }

    @Override
    public VoltPhysicalJoin copyWithLimitOffset(RelTraitSet traits, RexNode offset, RexNode limit) {
        ImmutableList<RelDataTypeField> systemFieldList = ImmutableList.copyOf(getSystemFieldList());
        return new VoltPhysicalNestLoopIndexJoin(getCluster(), traits, left, right, condition,
                variablesSet, joinType, isSemiJoinDone(), systemFieldList, m_innerIndex, m_accessPath, offset, limit);
    }

}
