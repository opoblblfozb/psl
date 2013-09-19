/*
 * This file is part of the PSL software.
 * Copyright 2011-2013 University of Maryland
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
package edu.umd.cs.psl.application.learning.weight.em;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.psl.application.learning.weight.maxlikelihood.VotedPerceptron;
import edu.umd.cs.psl.config.ConfigBundle;
import edu.umd.cs.psl.database.Database;
import edu.umd.cs.psl.model.Model;
import edu.umd.cs.psl.model.atom.ObservedAtom;
import edu.umd.cs.psl.model.atom.RandomVariableAtom;
import edu.umd.cs.psl.model.kernel.GroundCompatibilityKernel;
import edu.umd.cs.psl.model.kernel.GroundKernel;
import edu.umd.cs.psl.model.kernel.linearconstraint.GroundValueConstraint;

/**
 * EM algorithm which fits a point distribution to the single most probable
 * assignment of truth values to the latent variables during the E-step. 
 * 
 * @author Stephen Bach <bach@cs.umd.edu>
 */
public class HardEM extends ExpectationMaximization {
	
	private static final Logger log = LoggerFactory.getLogger(HardEM.class);
	
	protected List<GroundValueConstraint> labelConstraints;

	public HardEM(Model model, Database rvDB, Database observedDB,
			ConfigBundle config) {
		super(model, rvDB, observedDB, config);
	}

	/**
	 * Minimizes the KL divergence by setting the latent variables to their
	 * most probable state conditioned on the evidence and the labeled
	 * random variables.
	 * <p>
	 * This method assumes that the inferred truth values will be used
	 * immediately by {@link VotedPerceptron#computeObservedIncomp()}.
	 */
	@Override
	protected void minimizeKLDivergence() {
		/* Adds constraints to fix values of labeled random variables */
		for (GroundValueConstraint con : labelConstraints)
			reasoner.addGroundKernel(con);
		
		/* Infers most probable assignment latent variables */
		reasoner.optimize();
		
		/* Removes constraints */
		for (GroundValueConstraint con : labelConstraints)
			reasoner.removeGroundKernel(con);
	}
	
	@Override
	protected void initGroundModel()
			throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		super.initGroundModel();
		
		/* Creates constraints to fix labeled random variables to their true values */
		labelConstraints = new ArrayList<GroundValueConstraint>();
		for (Map.Entry<RandomVariableAtom, ObservedAtom> e : trainingMap.getTrainingMap().entrySet())
			labelConstraints.add(new GroundValueConstraint(e.getKey(), e.getValue().getValue()));
	}

	@Override
	protected double[] computeExpectedIncomp() {
		double[] expIncomp = new double[kernels.size()];
		
		/* Computes the MPE state */
		reasoner.optimize();
		
		/* Computes incompatibility */
		for (int i = 0; i < kernels.size(); i++) {
			for (GroundKernel gk : reasoner.getGroundKernels(kernels.get(i))) {
				expIncomp[i] += ((GroundCompatibilityKernel) gk).getIncompatibility();
			}
		}
		
		return expIncomp;
	}

}
