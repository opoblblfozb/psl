/*
 * This file is part of the PSL software.
 * Copyright 2011-2015 University of Maryland
 * Copyright 2013-2020 The Regents of the University of California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.linqs.psl.reasoner.admm;

import org.linqs.psl.config.Options;
import org.linqs.psl.model.rule.GroundRule;
import org.linqs.psl.model.rule.WeightedGroundRule;
import org.linqs.psl.reasoner.Reasoner;
import org.linqs.psl.reasoner.admm.term.ADMMObjectiveTerm;
import org.linqs.psl.reasoner.admm.term.ADMMTermStore;
import org.linqs.psl.reasoner.admm.term.LinearConstraintTerm;
import org.linqs.psl.reasoner.admm.term.LocalVariable;
import org.linqs.psl.reasoner.term.TermGenerator;
import org.linqs.psl.reasoner.term.TermStore;
import org.linqs.psl.util.MathUtils;
import org.linqs.psl.util.Parallel;
import org.linqs.psl.util.RandUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses an ADMM optimization method to optimize its GroundRules.
 */
public class ADMMReasoner implements Reasoner {
    private static final Logger log = LoggerFactory.getLogger(ADMMReasoner.class);

    /**
     * Possible starting values for the consensus values.
     *  - ZERO - 0.
     *  - RANDOM - Uniform sample in [0, 1].
     *  - ATOM - The value of the RVA that backs this global variable.
     */
    public static enum InitialValue { ZERO, RANDOM, ATOM }

    private static final float LOWER_BOUND = 0.0f;
    private static final float UPPER_BOUND = 1.0f;

    private int computePeriod;

    /**
     * Sometimes called eta or rho,
     */
    private final float stepSize;

    private float epsilonRel;
    private float epsilonAbs;

    private float primalRes;
    private float epsilonPrimal;
    private float dualRes;
    private float epsilonDual;

    private float AxNorm;
    private float AyNorm;
    private float BzNorm;
    private float lagrangePenalty;
    private float augmentedLagrangePenalty;

    private int maxIter;

    // Also sometimes called 'z'.
    // Only populated after inference.
    private float[] consensusValues;

    private int termBlockSize;
    private int variableBlockSize;
    private boolean objectiveBreak;

    public ADMMReasoner() {
        maxIter = Options.ADMM_MAX_ITER.getInt();
        stepSize = Options.ADMM_STEP_SIZE.getFloat();
        computePeriod = Options.ADMM_COMPUTE_PERIOD.getInt();
        objectiveBreak = Options.ADMM_OBJECTIVE_BREAK.getBoolean();
        epsilonAbs = Options.ADMM_EPSILON_ABS.getFloat();
        epsilonRel = Options.ADMM_EPSILON_REL.getFloat();
    }

    public int getMaxIter() {
        return maxIter;
    }

    public void setMaxIter(int maxIter) {
        this.maxIter = maxIter;
    }

    public float getEpsilonRel() {
        return epsilonRel;
    }

    public void setEpsilonRel(float epsilonRel) {
        this.epsilonRel = epsilonRel;
    }

    public float getEpsilonAbs() {
        return epsilonAbs;
    }

    public void setEpsilonAbs(float epsilonAbs) {
        this.epsilonAbs = epsilonAbs;
    }

    public float getLagrangianPenalty() {
        return this.lagrangePenalty;
    }

    public float getAugmentedLagrangianPenalty() {
        return this.augmentedLagrangePenalty;
    }

    @Override
    public void optimize(TermStore baseTermStore) {
        InitialValue initialConsensus = InitialValue.valueOf(Options.ADMM_INITIAL_CONSENSUS_VALUE.getString().toUpperCase());
        InitialValue initialLocal = InitialValue.valueOf(Options.ADMM_INITIAL_LOCAL_VALUE.getString().toUpperCase());

        optimize(baseTermStore, initialConsensus, initialLocal);
    }

    public void optimize(TermStore baseTermStore, InitialValue initialConsensus, InitialValue initialLocal) {
        if (!(baseTermStore instanceof ADMMTermStore)) {
            throw new IllegalArgumentException("ADMMReasoner requires an ADMMTermStore (found " + baseTermStore.getClass().getName() + ").");
        }
        ADMMTermStore termStore = (ADMMTermStore)baseTermStore;

        termStore.resetLocalVairables(initialLocal);

        int numTerms = termStore.size();
        int numVariables = termStore.getNumGlobalVariables();

        log.debug("Performing optimization with {} variables and {} terms.", numVariables, numTerms);

        initConsensusValues(termStore, initialConsensus);

        termBlockSize = numTerms / (Parallel.getNumThreads() * 4) + 1;
        variableBlockSize = numVariables / (Parallel.getNumThreads() * 4) + 1;

        int numTermBlocks = (int)Math.ceil(numTerms / (float)termBlockSize);
        int numVariableBlocks = (int)Math.ceil(numVariables / (float)variableBlockSize);

        // Performs inference.
        float epsilonAbsTerm = (float)(Math.sqrt(termStore.getNumLocalVariables()) * epsilonAbs);

        ObjectiveResult objective = null;
        ObjectiveResult oldObjective = null;

        if (log.isTraceEnabled()) {
            objective = computeObjective(termStore, false);
            log.trace(
                    "Iteration {} -- Objective: {}, Feasible: {}.",
                    0, objective.objective, (objective.violatedConstraints == 0));
        }

        int iteration = 1;
        while (true) {
            // Zero out the iteration variables.
            primalRes = 0.0f;
            dualRes = 0.0f;
            AxNorm = 0.0f;
            AyNorm = 0.0f;
            BzNorm = 0.0f;
            lagrangePenalty = 0.0f;
            augmentedLagrangePenalty = 0.0f;

            // Minimize all the terms.
            Parallel.count(numTermBlocks, new TermWorker(termStore, termBlockSize));

            // Compute new consensus values and residuals.
            Parallel.count(numVariableBlocks, new VariableWorker(termStore, variableBlockSize));

            primalRes = (float)Math.sqrt(primalRes);
            dualRes = (float)(stepSize * Math.sqrt(dualRes));

            epsilonPrimal = (float)(epsilonAbsTerm + epsilonRel * Math.max(Math.sqrt(AxNorm), Math.sqrt(BzNorm)));
            epsilonDual = (float)(epsilonAbsTerm + epsilonRel * Math.sqrt(AyNorm));

            if (iteration % computePeriod == 0) {
                if (!objectiveBreak) {
                    log.trace(
                            "Iteration {} -- Primal: {}, Dual: {}, Epsilon Primal: {}, Epsilon Dual: {}.",
                            iteration, primalRes, dualRes, epsilonPrimal, epsilonDual);
                } else {
                    oldObjective = objective;
                    objective = computeObjective(termStore, false);

                    log.trace(
                            "Iteration {} -- Objective: {}, Feasible: {}, Primal: {}, Dual: {}, Epsilon Primal: {}, Epsilon Dual: {}.",
                            iteration, objective.objective, (objective.violatedConstraints == 0),
                            primalRes, dualRes, epsilonPrimal, epsilonDual);
                }
            }

            iteration++;

            if (breakOptimization(iteration, objective, oldObjective)) {
                // Before we break, compute the objective so we can look for violated constraints.
                objective = computeObjective(termStore, false);

                // Check one more time if we should actually break.
                if (breakOptimization(iteration, objective, oldObjective)) {
                    break;
                }
            }
        }

        log.info("Optimization completed in {} iterations. Objective: {}, Feasible: {}, Primal res.: {}, Dual res.: {}",
                iteration - 1, objective.objective, (objective.violatedConstraints == 0), primalRes, dualRes);

        if (objective.violatedConstraints > 0) {
            log.warn("No feasible solution found. {} constraints violated.", objective.violatedConstraints);
            computeObjective(termStore, true);
        }

        // Updates variables
        termStore.updateVariables(consensusValues);
    }

    private boolean breakOptimization(int iteration, ObjectiveResult objective, ObjectiveResult oldObjective) {
        // Always break when the allocated iterations is up.
        if (iteration > maxIter) {
            return true;
        }

        // Don't break if there are violated constraints.
        if (objective != null && objective.violatedConstraints > 0) {
            return false;
        }

        // Break if we have converged.
        if (iteration > 1 && primalRes < epsilonPrimal && dualRes < epsilonDual) {
            return true;
        }

        // Break if the objective has not changed.
        if (objectiveBreak && oldObjective != null && MathUtils.equals(objective.objective, oldObjective.objective)) {
            return true;
        }

        return false;
    }

    @Override
    public void close() {
    }

    /**
     * Computes the incompatibility of the local variable copies corresponding to GroundRule groundRule.
     * The caller should provide a buffer that will be used to keep copies of the consensus values.
     * It should be sized: termStore().getNumGlobalVariables().
     * Null may be passed instead, but it will cause an allocation.
     */
    public double getDualIncompatibility(GroundRule groundRule, ADMMTermStore termStore, float[] consensusBuffer) {
        if (consensusBuffer == null) {
            consensusBuffer = new float[termStore.getNumGlobalVariables()];
        }

        assert(consensusBuffer.length == consensusValues.length);

        // Set the global variables to the value of the local variables for this rule.
        for (ADMMObjectiveTerm term : termStore.getTerms(groundRule)) {
            for (LocalVariable localVariable : term.getVariables()) {
                consensusBuffer[localVariable.getGlobalId()] = localVariable.getValue();
            }
        }

        // Updates variables
        termStore.updateVariables(consensusBuffer);
        double incompatibility = ((WeightedGroundRule)groundRule).getIncompatibility();

        // Reset the variables to the correct values.
        termStore.updateVariables(consensusValues);

        return incompatibility;
    }

    private void initConsensusValues(ADMMTermStore termStore, InitialValue initialConsensus) {
        consensusValues = new float[termStore.getNumGlobalVariables()];

        if (initialConsensus == InitialValue.ZERO) {
            for (int i = 0; i < consensusValues.length; i++) {
                consensusValues[i] = 0.0f;
            }
        } else if (initialConsensus == InitialValue.RANDOM) {
            for (int i = 0; i < consensusValues.length; i++) {
                consensusValues[i] = RandUtils.nextFloat();
            }
        } else if (initialConsensus == InitialValue.ATOM) {
            termStore.getAtomValues(consensusValues);
        } else {
            throw new IllegalStateException("Unknown initial consensus value: " + initialConsensus);
        }
    }

    private ObjectiveResult computeObjective(ADMMTermStore termStore, boolean logViolatedConstraints) {
        float objective = 0.0f;
        int violatedConstraints = 0;

        for (ADMMObjectiveTerm term : termStore) {
            if (term instanceof LinearConstraintTerm) {
                if (term.evaluate(consensusValues) > 0.0f) {
                    violatedConstraints++;

                    if (logViolatedConstraints) {
                        log.trace("    {}", term.getGroundRule());
                    }
                }
            } else {
                objective += term.evaluate(consensusValues);
            }
        }

        return new ObjectiveResult(objective, violatedConstraints);
    }

    private synchronized void updateIterationVariables(
            float primalRes, float dualRes,
            float AxNorm, float BzNorm, float AyNorm,
            float lagrangePenalty, float augmentedLagrangePenalty) {
        this.primalRes += primalRes;
        this.dualRes += dualRes;
        this.AxNorm += AxNorm;
        this.AyNorm += AyNorm;
        this.BzNorm += BzNorm;
        this.lagrangePenalty += lagrangePenalty;
        this.augmentedLagrangePenalty += augmentedLagrangePenalty;
    }

    private class TermWorker extends Parallel.Worker<Integer> {
        private ADMMTermStore termStore;
        private int blockSize;

        public TermWorker(ADMMTermStore termStore, int blockSize) {
            super();
            this.termStore = termStore;
            this.blockSize = blockSize;
        }

        public Object clone() {
            return new TermWorker(termStore, blockSize);
        }

        @Override
        public void work(int blockIndex, Integer ignore) {
            int numTerms = termStore.size();

            // Minimize each local function (wrt the local variable copies).
            for (int innerBlockIndex = 0; innerBlockIndex < blockSize; innerBlockIndex++) {
                int termIndex = blockIndex * blockSize + innerBlockIndex;

                if (termIndex >= numTerms) {
                    break;
                }

                termStore.get(termIndex).updateLagrange(stepSize, consensusValues);
                termStore.get(termIndex).minimize(stepSize, consensusValues);
            }
        }
    }

    private class VariableWorker extends Parallel.Worker<Integer> {
        private ADMMTermStore termStore;
        private int blockSize;

        public VariableWorker(ADMMTermStore termStore, int blockSize) {
            super();
            this.termStore = termStore;
            this.blockSize = blockSize;
        }

        public Object clone() {
            return new VariableWorker(termStore, blockSize);
        }

        @Override
        public void work(int blockIndex, Integer ignore) {
            int numVariables = termStore.getNumGlobalVariables();

            float primalResInc = 0.0f;
            float dualResInc = 0.0f;
            float AxNormInc = 0.0f;
            float BzNormInc = 0.0f;
            float AyNormInc = 0.0f;
            float lagrangePenaltyInc = 0.0f;
            float augmentedLagrangePenaltyInc = 0.0f;

            // Instead of dividing up the work ahead of time,
            // get one job at a time so the threads will have more even workloads.
            for (int innerBlockIndex = 0; innerBlockIndex < blockSize; innerBlockIndex++) {
                int variableIndex = blockIndex * blockSize + innerBlockIndex;

                if (variableIndex >= numVariables) {
                    break;
                }

                float total = 0.0f;
                int numLocalVariables = termStore.getLocalVariables(variableIndex).size();

                // First pass computes newConsensusValue and dual residual fom all local copies.
                for (int localVarIndex = 0; localVarIndex < numLocalVariables; localVarIndex++) {
                    LocalVariable localVariable = termStore.getLocalVariables(variableIndex).get(localVarIndex);
                    total += localVariable.getValue() + localVariable.getLagrange() / stepSize;

                    AxNormInc += localVariable.getValue() * localVariable.getValue();
                    AyNormInc += localVariable.getLagrange() * localVariable.getLagrange();
                }

                float newConsensusValue = total / numLocalVariables;
                newConsensusValue = Math.max(Math.min(newConsensusValue, UPPER_BOUND), LOWER_BOUND);

                float diff = consensusValues[variableIndex] - newConsensusValue;
                // Residual is diff^2 * number of local variables mapped to consensusValues element.
                dualResInc += diff * diff * numLocalVariables;
                BzNormInc += newConsensusValue * newConsensusValue * numLocalVariables;

                consensusValues[variableIndex] = newConsensusValue;

                // Second pass computes primal residuals.

                for (int localVarIndex = 0; localVarIndex < numLocalVariables; localVarIndex++) {
                    LocalVariable localVariable = termStore.getLocalVariables(variableIndex).get(localVarIndex);

                    diff = localVariable.getValue() - newConsensusValue;
                    primalResInc += diff * diff;

                    // compute Lagrangian penalties
                    lagrangePenaltyInc += localVariable.getLagrange() * (localVariable.getValue() - consensusValues[variableIndex]);
                    augmentedLagrangePenaltyInc += 0.5 * stepSize * Math.pow(localVariable.getValue() - consensusValues[variableIndex], 2);
                }
            }

            updateIterationVariables(primalResInc, dualResInc, AxNormInc, BzNormInc, AyNormInc, lagrangePenaltyInc, augmentedLagrangePenaltyInc);
        }
    }

    private static class ObjectiveResult {
        public final float objective;
        public final int violatedConstraints;

        public ObjectiveResult(float objective, int violatedConstraints) {
            this.objective = objective;
            this.violatedConstraints = violatedConstraints;
        }
    }
}