/*
 * This file is part of the PSL software.
 * Copyright 2011-2015 University of Maryland
 * Copyright 2013-2022 The Regents of the University of California
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
package org.linqs.psl.runtime;

import org.linqs.psl.config.RuntimeOptions;
import org.linqs.psl.database.AtomStore;
import org.linqs.psl.database.DataStore;
import org.linqs.psl.database.Database;
import org.linqs.psl.database.Partition;
import org.linqs.psl.database.loading.Inserter;
import org.linqs.psl.database.rdbms.RDBMSDataStore;
import org.linqs.psl.database.rdbms.driver.H2DatabaseDriver;
import org.linqs.psl.database.rdbms.driver.H2DatabaseDriver.Type;
import org.linqs.psl.grounding.GroundRuleStore;
import org.linqs.psl.grounding.Grounding;
import org.linqs.psl.grounding.MemoryGroundRuleStore;
import org.linqs.psl.model.predicate.StandardPredicate;
import org.linqs.psl.model.atom.GroundAtom;
import org.linqs.psl.model.rule.GroundRule;
import org.linqs.psl.model.rule.Rule;
import org.linqs.psl.model.rule.arithmetic.AbstractGroundArithmeticRule;
import org.linqs.psl.model.rule.logical.AbstractGroundLogicalRule;
import org.linqs.psl.model.term.Constant;
import org.linqs.psl.model.term.ConstantType;
import org.linqs.psl.model.term.UniqueStringID;
import org.linqs.psl.parser.ModelLoader;
import org.linqs.psl.util.Logger;
import org.linqs.psl.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * A static-only class that gives easy access to PSL's grounding functionality.
 *
 * TODO(eriq): There is a crazy amount of improvements that can be made here,
 * we will just leave this comment instead of pointing out everything.
 * Things like forced options and copied code (e.g. from the Launcher).
 *
 * TODO(eriq): Implement this using the Runtime (add a grounding stage to the runtime).
 */
public final class GroundingAPI {
    public static final String PARTITION_OBS = "observed";
    public static final String PARTITION_UNOBS = "unobserved";

    // Static only.
    public GroundingAPI() {}

    /**
     * Compute ground rules for an external process.
     * |atoms| is formatted as: [[atom id, relation index, value], ...].
     */
    public static GroundRuleInfo[] ground(
            String[] ruleStrings,
            String[] predicateNames, int[] predicateArities,
            String[][] atoms, String[][] atomArguments) {
        Logger.setLevel("WARN");

        assert(predicateNames.length == predicateArities.length);

        DataStore dataStore = new RDBMSDataStore(new H2DatabaseDriver(Type.Disk, RuntimeOptions.DB_H2_PATH.defaultValue().toString(), true));

        registerPredicates(predicateNames, predicateArities, dataStore);

        List<Rule> rules = new ArrayList<Rule>(ruleStrings.length);
        for (String ruleString : ruleStrings) {
            Rule rule = ModelLoader.loadRule(ruleString);
            rules.add(rule);
        }

        Set<StandardPredicate> closedPredicates = loadData(dataStore, predicateNames, atoms, atomArguments);

        Partition targetPartition = dataStore.getPartition(PARTITION_UNOBS);
        Partition observationsPartition = dataStore.getPartition(PARTITION_OBS);
        Database database = dataStore.getDatabase(targetPartition, closedPredicates, observationsPartition);

        GroundRuleStore groundRuleStore = new MemoryGroundRuleStore();

        Map<GroundAtom, Integer> atomMap = buildAtomMap(predicateNames, atoms, atomArguments, database);

        Grounding.groundAll(rules, database, groundRuleStore);
        GroundRuleInfo[] groundRules = mapGroundRules(rules, atomMap, groundRuleStore);

        groundRuleStore.close();
        database.close();
        dataStore.close();

        return groundRules;
    }

    private static void registerPredicates(String[] predicateNames, int[] predicateArities, DataStore dataStore) {
        for (int i = 0; i < predicateNames.length; i++) {
            ConstantType[] types = new ConstantType[predicateArities[i]];
            for (int j = 0; j < types.length; j++) {
                types[j] = ConstantType.UniqueStringID;
            }

            StandardPredicate predicate = StandardPredicate.get(predicateNames[i], types);
            dataStore.registerPredicate(predicate);
        }
    }

    private static Set<StandardPredicate> loadData(
            DataStore dataStore, String[] predicateNames,
            String[][] atoms, String[][] atomArguments) {
        Set<StandardPredicate> observedPredicates = new HashSet<StandardPredicate>(predicateNames.length);

        for (int predicateIndex = 0; predicateIndex < predicateNames.length; predicateIndex++) {
            StandardPredicate predicate = StandardPredicate.get(predicateNames[predicateIndex]);
            boolean isObserved = true;

            Object[] insertBuffer = new Object[predicate.getArity()];

            Inserter obsInserter = dataStore.getInserter(predicate, dataStore.getPartition(PARTITION_OBS));
            Inserter unobsInserter = dataStore.getInserter(predicate, dataStore.getPartition(PARTITION_UNOBS));

            for (int atomIndex = 0; atomIndex < atoms.length; atomIndex++) {
                if (predicateIndex != Integer.parseInt(atoms[atomIndex][1])) {
                    continue;
                }

                int id = Integer.parseInt(atoms[atomIndex][0]);

                Double value = null;
                if (atoms[atomIndex][2].length() > 0) {
                    value = Double.parseDouble(atoms[atomIndex][2]);
                }

                for (int i = 0; i < predicate.getArity(); i++) {
                    insertBuffer[i] = atomArguments[atomIndex][i];
                }

                // Observed atoms all hae values.
                if (value == null) {
                    isObserved = false;
                    unobsInserter.insert(insertBuffer);
                } else {
                    obsInserter.insertValue(value, insertBuffer);
                }
            }

            if (isObserved) {
                observedPredicates.add(predicate);
            }
        }

        return observedPredicates;
    }

    private static Map<GroundAtom, Integer> buildAtomMap(
            String[] predicateNames,
            String[][] atoms, String[][] atomArguments,
            Database database) {
        AtomStore atomStore = database.getAtomStore();

        // Each atom gets mapped in a reversible way to an index.
        // These indexes are contiguous, go through the predicates in-order, handle obs, and finally unobs.
        int atomCount = atomStore.size();
        Map<GroundAtom, Integer> atomMap = new HashMap<GroundAtom, Integer>(atomCount);

        for (int predicateIndex = 0; predicateIndex < predicateNames.length; predicateIndex++) {
            StandardPredicate predicate = StandardPredicate.get(predicateNames[predicateIndex]);
            Constant[] arguments = new Constant[predicate.getArity()];

            for (int atomIndex = 0; atomIndex < atoms.length; atomIndex++) {
                if (predicateIndex != Integer.parseInt(atoms[atomIndex][1])) {
                    continue;
                }

                int id = Integer.parseInt(atoms[atomIndex][0]);


                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = new UniqueStringID(atomArguments[atomIndex][i]);
                }

                GroundAtom atom = atomStore.getAtom(predicate, arguments);
                atomMap.put(atom, Integer.valueOf(id));
            }
        }

        return atomMap;
    }

    private static GroundRuleInfo[] mapGroundRules(
            List<Rule> rules,
            Map<GroundAtom, Integer> atomMap,
            GroundRuleStore groundRuleStore) {
        GroundRuleInfo[] infos = new GroundRuleInfo[(int)groundRuleStore.size()];

        int groundRuleCount = 0;
        for (GroundRule rawGroundRule : groundRuleStore.getGroundRules()) {
            if (rawGroundRule instanceof AbstractGroundLogicalRule) {
                infos[groundRuleCount++] = mapLogicalGroundRule(
                        rules.indexOf(rawGroundRule.getRule()), atomMap, (AbstractGroundLogicalRule)rawGroundRule);
            } else {
                infos[groundRuleCount++] = mapArithmeticGroundRule(
                        rules.indexOf(rawGroundRule.getRule()), atomMap, (AbstractGroundArithmeticRule)rawGroundRule);
            }
        }

        return infos;
    }

    private static GroundRuleInfo mapLogicalGroundRule(
            int ruleIndex,
            Map<GroundAtom, Integer> atomMap,
            AbstractGroundLogicalRule groundRule) {
        int atomIndex = 0;
        float[] coefficients = new float[groundRule.size()];
        int[] atoms = new int[groundRule.size()];

        for (GroundAtom atom : groundRule.getPositiveAtoms()) {
            coefficients[atomIndex] = 1.0f;
            atoms[atomIndex] = atomMap.get(atom).intValue();
            atomIndex++;
        }

        for (GroundAtom atom : groundRule.getNegativeAtoms()) {
            coefficients[atomIndex] = -1.0f;
            atoms[atomIndex] = atomMap.get(atom).intValue();
            atomIndex++;
        }

        return new GroundRuleInfo(ruleIndex, "|", 0.0f, coefficients, atoms);
    }

    private static GroundRuleInfo mapArithmeticGroundRule(
            int ruleIndex,
            Map<GroundAtom, Integer> atomMap,
            AbstractGroundArithmeticRule groundRule) {
        GroundAtom[] rawAtoms = groundRule.getOrderedAtoms();
        int[] atoms = new int[rawAtoms.length];

        for (int i = 0; i < rawAtoms.length; i++) {
            atoms[i] = atomMap.get(rawAtoms[i]).intValue();
        }

        return new GroundRuleInfo(ruleIndex, groundRule.getComparator().toString(), groundRule.getConstant(),
                groundRule.getCoefficients(), atoms);
    }

    public static final class GroundRuleInfo {
        public int ruleIndex;
        public String operator;
        public float constant;
        public float[] coefficients;
        public int[] atoms;

        public GroundRuleInfo(int ruleIndex, String operator, float constant, float[] coefficients, int[] atoms) {
            this.ruleIndex = ruleIndex;
            this.operator = operator;
            this.constant = constant;
            this.coefficients = coefficients;
            this.atoms = atoms;
        }

        public String toString() {
            return String.format(
                    "Rule: %d, Operator: %s, Constant: %f, coefficients: [%s], atoms: [%s].",
                    ruleIndex, operator, constant,
                    StringUtils.join(", ", coefficients), StringUtils.join(", ", atoms));
        }
    }
}
