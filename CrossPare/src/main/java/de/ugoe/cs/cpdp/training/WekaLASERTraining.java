// Copyright 2015 Georg-August-Universität Göttingen, Germany
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.

package de.ugoe.cs.cpdp.training;

import java.util.LinkedList;
import java.util.List;

import de.ugoe.cs.cpdp.util.WekaUtils;
import de.ugoe.cs.cpdp.versions.SoftwareVersion;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;

/**
 * <p>
 * Implements training following the LASER classification scheme.
 * </p>
 * 
 * @author Steffen Herbold
 */
public class WekaLASERTraining extends WekaBaseTraining implements ITrainingStrategy {

    /**
     * Internal classifier used for LASER.
     */
    private final LASERClassifier internalClassifier = new LASERClassifier();

    /*
     * (non-Javadoc)
     * 
     * @see de.ugoe.cs.cpdp.training.WekaBaseTraining#getClassifier()
     */
    @Override
    public Classifier getClassifier() {
        return this.internalClassifier;
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.ugoe.cs.cpdp.training.ITrainingStrategy#apply(de.ugoe.cs.cpdp.versions.SoftwareVersion)
     */
    @Override
    public void apply(SoftwareVersion trainversion) {
        try {
            this.internalClassifier.buildClassifier(trainversion.getInstances());
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * <p>
     * Internal helper class that defines the laser classifier.
     * </p>
     * 
     * @author Steffen Herbold
     */
    public class LASERClassifier extends AbstractClassifier {

        /**
         * Default serial ID.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Internal reference to the classifier.
         */
        private Classifier laserClassifier = null;

        /**
         * Internal storage of the training data required for NN analysis.
         */
        private Instances traindata = null;

        /*
         * (non-Javadoc)
         * 
         * @see weka.classifiers.AbstractClassifier#classifyInstance(weka.core.Instance)
         */
        @SuppressWarnings("boxing")
        @Override
        public double classifyInstance(Instance instance) throws Exception {
            List<Integer> closestInstances = new LinkedList<>();
            double minDistance = Double.MAX_VALUE;
            for (int i = 0; i < this.traindata.size(); i++) {
                double distance = WekaUtils.hammingDistance(instance, this.traindata.get(i));
                if (distance < minDistance) {
                    minDistance = distance;
                }
            }
            for (int i = 0; i < this.traindata.size(); i++) {
                double distance = WekaUtils.hammingDistance(instance, this.traindata.get(i));
                if (distance <= minDistance) {
                    closestInstances.add(i);
                }
            }
            if (closestInstances.size() == 1) {
                int closestIndex = closestInstances.get(0);
                Instance closestTrainingInstance = this.traindata.get(closestIndex);
                List<Integer> closestToTrainingInstance = new LinkedList<>();
                double minTrainingDistance = Double.MAX_VALUE;
                for (int i = 0; i < this.traindata.size(); i++) {
                    if (closestIndex != i) {
                        double distance =
                            WekaUtils.hammingDistance(closestTrainingInstance, this.traindata.get(i));
                        if (distance < minTrainingDistance) {
                            minTrainingDistance = distance;
                        }
                    }
                }
                for (int i = 0; i < this.traindata.size(); i++) {
                    if (closestIndex != i) {
                        double distance =
                            WekaUtils.hammingDistance(closestTrainingInstance, this.traindata.get(i));
                        if (distance <= minTrainingDistance) {
                            closestToTrainingInstance.add(i);
                        }
                    }
                }
                if (closestToTrainingInstance.size() == 1) {
                    return this.laserClassifier.classifyInstance(instance);
                }
                double label = Double.NaN;
                boolean allEqual = true;
                for (Integer index : closestToTrainingInstance) {
                    if (Double.isNaN(label)) {
                        label = this.traindata.get(index).classValue();
                    }
                    else if (label != this.traindata.get(index).classValue()) {
                        allEqual = false;
                        break;
                    }
                }
                if (allEqual) {
                    return label;
                }
                return this.laserClassifier.classifyInstance(instance);
            }
            double label = Double.NaN;
            boolean allEqual = true;
            for (Integer index : closestInstances) {
                if (Double.isNaN(label)) {
                    label = this.traindata.get(index).classValue();
                }
                else if (label != this.traindata.get(index).classValue()) {
                    allEqual = false;
                    break;
                }
            }
            if (allEqual) {
                return label;
            }
            return this.laserClassifier.classifyInstance(instance);
        }

        /*
         * (non-Javadoc)
         * 
         * @see weka.classifiers.Classifier#buildClassifier(weka.core.Instances)
         */
        @SuppressWarnings("hiding")
        @Override
        public void buildClassifier(Instances traindata) throws Exception {
            this.traindata = new Instances(traindata);
            this.laserClassifier = setupClassifier();
            this.laserClassifier = WekaUtils.buildClassifier(this.laserClassifier, traindata);
        }
    }
}
