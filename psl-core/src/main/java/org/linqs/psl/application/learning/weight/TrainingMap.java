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
package org.linqs.psl.application.learning.weight;

import org.linqs.psl.database.Database;
import org.linqs.psl.database.atom.PersistedAtomManager;
import org.linqs.psl.model.atom.GroundAtom;
import org.linqs.psl.model.atom.ObservedAtom;
import org.linqs.psl.model.atom.RandomVariableAtom;
import org.linqs.psl.model.predicate.StandardPredicate;
import org.linqs.psl.util.IteratorUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class that keeps tracks of the mapping between an RVA in one database (atom manager)
 * and an observed atom in another (as well as unmapped (latent) variables).
 * Any unobserved atoms in the truth database will be ignored.
 *
 * There are six situations that can arise when comparing the atoms from both databases.
 * Here we will enumerate all the possibilities.
 * The first item of the tuple represents the target database (atom manager),
 * while the second item represents the truth database.
 * The right hand side will say where this pair will be recorded.
 *   (unobserved, observed) - Standard Label Map
 *   (unobserved, not existent) - Latent Variables
 *   (observed, observed) - Observed Map
 *   (observed, not existent) - Missing Labels
 *   (not existent, observed) - Missing Targets
 *   (not existent, not existent) - Ignored
 */
public class TrainingMap {
    /**
     * The mapping between an RVA and its observed truth atom.
     */
    private final Map<RandomVariableAtom, ObservedAtom> labelMap;

    /**
     * A mapping like the label mapping, but only contains target atoms that are observed.
     */
    private final Map<ObservedAtom, ObservedAtom> observedMap;

    /**
     * The set of atoms that have no associated observed truth atom.
     */
    private final List<RandomVariableAtom> latentVariables;

    /**
     * Observed targets that do not have an associated observed truth atom.
     */
    private final List<ObservedAtom> missingLabels;

    /**
     * Observed truth atoms that do not have an associated target atom.
     */
    private final List<ObservedAtom> missingTargets;

    /**
     * Initializes the training map of RandomVariableAtoms ObservedAtoms.
     *
     * @param targets the atom manager containing the RandomVariableAtoms (any other atom types are ignored)
     * @param truthDatabase the database containing matching ObservedAtoms
     */
    public TrainingMap(PersistedAtomManager targets, Database truthDatabase) {
        Map<RandomVariableAtom, ObservedAtom> tempLabelMap = new HashMap<RandomVariableAtom, ObservedAtom>(targets.getPersistedCount());
        Map<ObservedAtom, ObservedAtom> tempObservedMap = new HashMap<ObservedAtom, ObservedAtom>();
        List<RandomVariableAtom> tempLatentVariables = new ArrayList<RandomVariableAtom>();
        List<ObservedAtom> tempMissingLabels = new ArrayList<ObservedAtom>();
        List<ObservedAtom> tempMissingTargets = new ArrayList<ObservedAtom>();

        Set<GroundAtom> seenTruthAtoms = new HashSet<GroundAtom>();

        for (GroundAtom targetAtom : targets.getDatabase().getAllCachedAtoms()) {
            // Note that we do not want to create a non-existent atom.
            GroundAtom truthAtom = truthDatabase.getAtom((StandardPredicate)targetAtom.getPredicate(), false, targetAtom.getArguments());

            // Skip any truth atom that is not observed.
            if (truthAtom != null && !(truthAtom instanceof ObservedAtom)) {
                continue;
            }

            if (targetAtom instanceof RandomVariableAtom) {
                if (truthAtom == null) {
                    tempLatentVariables.add((RandomVariableAtom)targetAtom);
                } else {
                    seenTruthAtoms.add((ObservedAtom)truthAtom);
                    tempLabelMap.put((RandomVariableAtom)targetAtom, (ObservedAtom)truthAtom);
                }
            } else {
                if (truthAtom == null) {
                    tempMissingLabels.add((ObservedAtom)targetAtom);
                } else {
                    seenTruthAtoms.add((ObservedAtom)truthAtom);
                    tempObservedMap.put((ObservedAtom)targetAtom, (ObservedAtom)truthAtom);
                }
            }
        }

        for (StandardPredicate predicate : truthDatabase.getDataStore().getRegisteredPredicates()) {
            for (GroundAtom truthAtom : truthDatabase.getAllGroundAtoms(predicate)) {
                if (!(truthAtom instanceof ObservedAtom) || seenTruthAtoms.contains(truthAtom)) {
                    continue;
                }

                boolean hasAtom = targets.getDatabase().hasAtom((StandardPredicate)truthAtom.getPredicate(), truthAtom.getArguments());
                if (hasAtom) {
                    // This shouldn't be possible (since we already iterated through the target atoms.
                    throw new IllegalStateException("Un-persisted target atom: " + truthAtom);
                }

                tempMissingTargets.add((ObservedAtom)truthAtom);
            }
        }

        // Finalize the structures.
        labelMap = Collections.unmodifiableMap(tempLabelMap);
        observedMap = Collections.unmodifiableMap(tempObservedMap);
        latentVariables = Collections.unmodifiableList(tempLatentVariables);
        missingLabels = Collections.unmodifiableList(tempMissingLabels);
        missingTargets = Collections.unmodifiableList(tempMissingTargets);
    }

    /**
     * Get the mapping of unobserved targets to truth atoms.
     */
    public Map<RandomVariableAtom, ObservedAtom> getLabelMap() {
        return labelMap;
    }

    /**
     * Get the mapping of observed targets to truth atoms.
     */
    public Map<ObservedAtom, ObservedAtom> getObservedMap() {
        return observedMap;
    }

    /**
     * Gets the latent variables (unobserved targets without a truth atom).
     */
    public List<RandomVariableAtom> getLatentVariables() {
        return latentVariables;
    }

    /**
     * Gets observed targets that do not have an associated observed truth atom.
     */
    public List<ObservedAtom> getMissingLabels() {
        return missingLabels;
    }

    /**
     * Gets observed truth atoms that do not have an associated target atom.
     */
    public List<ObservedAtom> getMissingTargets() {
        return missingTargets;
    }

    /**
     * Get all the predictions (unobserved targets).
     * This combines atoms from the label map and latent variables.
     */
    public Iterable<RandomVariableAtom> getAllPredictions() {
        return IteratorUtils.join(labelMap.keySet(), latentVariables);
    }

    /**
     * Get the full mapping of target to truth atoms (unobserved and observed).
     */
    // Casting non-static subclasses (ie Mep.Entry) can get iffy, so we just brute forced the cast using Object.
    @SuppressWarnings("unchecked")
    public Iterable<Map.Entry<GroundAtom, GroundAtom>> getFullMap() {
        Iterable<Map.Entry<? extends GroundAtom, ? extends GroundAtom>> temp = IteratorUtils.join(
                (Collection<Map.Entry<? extends GroundAtom, ? extends GroundAtom>>)((Object)(labelMap.entrySet())),
                (Collection<Map.Entry<? extends GroundAtom, ? extends GroundAtom>>)((Object)(observedMap.entrySet()))
        );

        return (Iterable<Map.Entry<GroundAtom, GroundAtom>>)((Object)temp);
    }
}