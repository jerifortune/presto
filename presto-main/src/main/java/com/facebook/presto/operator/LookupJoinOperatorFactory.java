/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.operator.LookupJoinOperators.JoinType;
import com.facebook.presto.operator.LookupOuterOperator.LookupOuterOperatorFactory;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.operator.LookupJoinOperators.JoinType.INNER;
import static com.facebook.presto.operator.LookupJoinOperators.JoinType.PROBE_OUTER;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.Futures.transformAsync;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;

public class LookupJoinOperatorFactory
        implements JoinOperatorFactory
{
    private final int operatorId;
    private final PlanNodeId planNodeId;
    private final List<Type> probeTypes;
    private final List<Type> probeOutputTypes;
    private final List<Type> buildTypes;
    private final List<Type> buildOutputTypes;
    private final JoinType joinType;
    private final LookupSourceFactory lookupSourceFactory;
    private final JoinProbeFactory joinProbeFactory;
    private final Optional<OperatorFactory> outerOperatorFactory;
    private final ReferenceCount probeReferenceCount;
    private final ReferenceCount lookupSourceFactoryUsersCount;
    private boolean closed;

    public LookupJoinOperatorFactory(int operatorId,
            PlanNodeId planNodeId,
            LookupSourceFactory lookupSourceFactory,
            List<Type> probeTypes,
            List<Type> probeOutputTypes,
            JoinType joinType,
            JoinProbeFactory joinProbeFactory)
    {
        this.operatorId = operatorId;
        this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
        this.lookupSourceFactory = requireNonNull(lookupSourceFactory, "lookupSourceFactory is null");
        this.probeTypes = ImmutableList.copyOf(requireNonNull(probeTypes, "probeTypes is null"));
        this.probeOutputTypes = ImmutableList.copyOf(requireNonNull(probeOutputTypes, "probeOutputTypes is null"));
        this.buildTypes = ImmutableList.copyOf(lookupSourceFactory.getTypes());
        this.buildOutputTypes = ImmutableList.copyOf(lookupSourceFactory.getOutputTypes());
        this.joinType = requireNonNull(joinType, "joinType is null");
        this.joinProbeFactory = requireNonNull(joinProbeFactory, "joinProbeFactory is null");

        probeReferenceCount = new ReferenceCount();
        lookupSourceFactoryUsersCount = new ReferenceCount();

        // when all probe and build-outer operators finish, destroy the lookup source (freeing the memory)
        lookupSourceFactoryUsersCount.getFreeFuture().addListener(lookupSourceFactory::destroy, directExecutor());

        // Whole probe side is counted as 1 in lookupSourceFactoryUsersCount
        probeReferenceCount.getFreeFuture().addListener(lookupSourceFactoryUsersCount::release, directExecutor());

        if (joinType == INNER || joinType == PROBE_OUTER) {
            this.outerOperatorFactory = Optional.empty();
        }
        else {
            // when all join operators finish (and lookup source is ready), set the outer position future to start the outer operator
            ListenableFuture<LookupSource> lookupSourceAfterProbeFinished = transformAsync(probeReferenceCount.getFreeFuture(), ignored -> lookupSourceFactory.createLookupSource());
            ListenableFuture<OuterPositionIterator> outerPositionsFuture = transform(lookupSourceAfterProbeFinished, lookupSource -> {
                lookupSource.close();
                return lookupSourceFactory.getOuterPositionIterator();
            });

            lookupSourceFactoryUsersCount.retain();
            this.outerOperatorFactory = Optional.of(new LookupOuterOperatorFactory(operatorId, planNodeId, outerPositionsFuture, probeOutputTypes, buildOutputTypes, lookupSourceFactoryUsersCount));
        }
    }

    private LookupJoinOperatorFactory(LookupJoinOperatorFactory other)
    {
        requireNonNull(other, "other is null");
        operatorId = other.operatorId;
        planNodeId = other.planNodeId;
        probeTypes = other.probeTypes;
        probeOutputTypes = other.probeOutputTypes;
        buildTypes = other.buildTypes;
        buildOutputTypes = other.buildOutputTypes;
        joinType = other.joinType;
        lookupSourceFactory = other.lookupSourceFactory;
        joinProbeFactory = other.joinProbeFactory;
        probeReferenceCount = other.probeReferenceCount;
        lookupSourceFactoryUsersCount = other.lookupSourceFactoryUsersCount;
        outerOperatorFactory = other.outerOperatorFactory;

        probeReferenceCount.retain();
    }

    public int getOperatorId()
    {
        return operatorId;
    }

    @Override
    public List<Type> getTypes()
    {
        return ImmutableList.<Type>builder()
                .addAll(probeOutputTypes)
                .addAll(buildOutputTypes)
                .build();
    }

    @Override
    public Operator createOperator(DriverContext driverContext)
    {
        checkState(!closed, "Factory is already closed");
        OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, LookupJoinOperator.class.getSimpleName());

        lookupSourceFactory.setTaskContext(driverContext.getPipelineContext().getTaskContext());

        probeReferenceCount.retain();
        return new LookupJoinOperator(
                operatorContext,
                getTypes(),
                joinType,
                lookupSourceFactory.createLookupSource(),
                joinProbeFactory,
                probeReferenceCount::release);
    }

    @Override
    public void close()
    {
        if (closed) {
            return;
        }
        closed = true;
        probeReferenceCount.release();
    }

    @Override
    public OperatorFactory duplicate()
    {
        return new LookupJoinOperatorFactory(this);
    }

    @Override
    public Optional<OperatorFactory> createOuterOperatorFactory()
    {
        return outerOperatorFactory;
    }
}
