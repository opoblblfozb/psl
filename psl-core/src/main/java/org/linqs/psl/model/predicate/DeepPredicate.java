/*
 * This file is part of the PSL software.
 * Copyright 2011-2015 University of Maryland
 * Copyright 2013-2023 The Regents of the University of California
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
package org.linqs.psl.model.predicate;

import org.linqs.psl.database.AtomStore;
import org.linqs.psl.model.deep.DeepModelPredicate;
import org.linqs.psl.model.term.ConstantType;
import org.linqs.psl.util.Logger;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;

/**
 * A predicate that is backed by some deep model.
 */
public class DeepPredicate extends StandardPredicate {
    private static final Logger log = Logger.getLogger(DeepPredicate.class);

    private DeepModelPredicate deepModel;

    protected DeepPredicate(String name, ConstantType[] types) {
        super(name, types);
        deepModel = new DeepModelPredicate(this);
    }

    /**
     * Initialize the deep model.
     * The application is a string that is used to identify the initialization settings.
     */
    public void initDeepPredicate(AtomStore atomStore, String application){
        deepModel.setAtomStore(atomStore);
        deepModel.init(application);
    }

    /**
     * Put the neural model in training mode.
     */
    public void trainMode() {
        deepModel.trainMode();
    }

    /**
     * Put the neural model in evaluation mode.
     */
    public void evalMode() {
        deepModel.evalMode();
    }

    public void fitDeepPredicate(float[] symbolicGradients) {
        deepModel.setSymbolicGradients(symbolicGradients);
        deepModel.fitDeepModel();
    }

    /**
     * Let the neural model know that a batch has ended.
     */
    public void nextBatch() {
        deepModel.nextBatch();
    }

    /**
     * Let the neural model know that an epoch is starting.
     */
    public void epochStart() {
        deepModel.epochStart();
    }

    /**
     * Let the neural model know that an epoch has ended.
     */
    public void epochEnd() {
        deepModel.epochEnd();
    }

    /**
     * Check if the neural model has iterated over an entire epoch.
     */
    public boolean isEpochComplete() {
        return deepModel.isEpochComplete();
    }

    public DeepModelPredicate getDeepModel() {
        return deepModel;
    }

    public void setDeepModel(DeepModelPredicate deepModel) {
        this.deepModel = deepModel;
    }

    public float predictDeepModel() {
        return deepModel.predictDeepModel();
    }

    public float evalDeepModel() {
        return deepModel.evalDeepModel();
    }

    public void saveDeepModel() {
        deepModel.saveDeepModel();
    }

    @Override
    public synchronized void close() {
        super.close();
        deepModel.close();
    }

    /**
     * Get an existing standard predicate (or null if none with this name exists).
     * If the predicate exists, but is not a DeepPredicate, an exception will be thrown.
     */
    public static DeepPredicate get(String name) {
        StandardPredicate predicate = StandardPredicate.get(name);
        if (predicate == null) {
            return null;
        }

        if (!(predicate instanceof DeepPredicate)) {
            throw new ClassCastException("Predicate (" + name + ") is not a DeepPredicate.");
        }

        return (DeepPredicate)predicate;
    }

    /**
     * Get a predicate if one already exists, otherwise create a new one.
     */
    public static DeepPredicate get(String name, ConstantType... types) {
        DeepPredicate predicate = get(name);
        if (predicate == null) {
            return new DeepPredicate(name, types);
        }

        StandardPredicate.validateTypes(predicate, types);

        return predicate;
    }

    /**
     * Initialize all DeepPredicates.
     */
    public static void initAllDeepPredicates(AtomStore atomStore, String application) {
        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                ((DeepPredicate) predicate).initDeepPredicate(atomStore, application);
            }
        }
    }

    /**
     * Put all DeepPredicates in training mode.
     */
    public static void trainModeAllDeepPredicates() {
        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                ((DeepPredicate) predicate).trainMode();
            }
        }
    }

    /**
     * Put all DeepPredicates in eval mode.
     */
    public static void evalModeAllDeepPredicates() {
        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                ((DeepPredicate) predicate).evalMode();
            }
        }
    }

    /**
     * Let all DeepPredicates know that an epoch is starting.
     */
    public static void epochStartAllDeepPredicates() {
        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                ((DeepPredicate) predicate).epochStart();
            }
        }
    }

    /**
     * Let all DeepPredicates know that an epoch has ended.
     */
    public static void epochEndAllDeepPredicates() {
        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                ((DeepPredicate) predicate).epochEnd();
            }
        }
    }

    /**
     * Predict with all DeepPredicates.
     */
    public static float predictAllDeepPredicates() {
        float movement = 0.0f;

        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                movement += ((DeepPredicate) predicate).predictDeepModel();
            }
        }

        return movement;
    }

    /**
     * Evaluate all DeepPredicates.
     */
    public static void evalAllDeepPredicates() {
        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                ((DeepPredicate) predicate).evalDeepModel();
            }
        }
    }

    /**
     * Ready the next batch for all DeepPredicates.
     */
    public static void nextBatchAllDeepPredicates() {
        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                ((DeepPredicate) predicate).nextBatch();
            }
        }
    }

    /**
     * Check if the epoch is complete for all DeepPredicates.
     * If the epoch is complete for one DeepPredicate, it is complete for all.
     */
    public static boolean isEpochCompleteAllDeepPredicates() {
        boolean isEpochComplete = false;
        boolean deepPredicates = false;

        for (Predicate predicate : Predicate.getAll()) {
            if (predicate instanceof DeepPredicate) {
                deepPredicates = true;

                isEpochComplete = (((DeepPredicate) predicate).isEpochComplete());

                if (isEpochComplete) {
                    break;
                }
            }
        }

        return (!deepPredicates) || isEpochComplete;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new NotSerializableException();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        throw new NotSerializableException();
    }

    private void readObjectNoData() throws ObjectStreamException {
        throw new NotSerializableException();
    }
}