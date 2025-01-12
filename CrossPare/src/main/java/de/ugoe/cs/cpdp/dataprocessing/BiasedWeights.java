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

package de.ugoe.cs.cpdp.dataprocessing;

import org.apache.commons.collections4.list.SetUniqueList;

import de.ugoe.cs.cpdp.versions.SoftwareVersion;
import weka.core.Instance;
import weka.core.Instances;

/**
 * Sets the bias of the weights of the training data. By using a bias of 0.5 (default value) the
 * total weight of the positive instances (i.e. fault-prone) is equal to the total weight of the
 * negative instances (i.e. non-fault-prone). Otherwise the weights between the two will be
 * distributed according to the bias, where &lt;0.5 means in favor of the negative instances and
 * &gt;0.5 in favor of the positive instances. equal to the total weight of the test
 * 
 * @author Steffen Herbold
 */
public class BiasedWeights implements IProcessesingStrategy, ISetWiseProcessingStrategy {

    /**
     * bias used for the weighting
     */
    private double bias = 0.5;

    /**
     * Sets the bias to be used for weighting.
     * 
     * @param parameters
     *            string with the bias
     */
    @Override
    public void setParameter(String parameters) {
        this.bias = Double.parseDouble(parameters);
    }

    /**
     * @see IProcessesingStrategy#apply(de.ugoe.cs.cpdp.versions.SoftwareVersion,
     *      de.ugoe.cs.cpdp.versions.SoftwareVersion)
     */
    @Override
    public void apply(SoftwareVersion testversion, SoftwareVersion trainversion) {
        // setBiasedWeights(testdata);
        setBiasedWeights(trainversion);
    }

    /**
     * @see ISetWiseProcessingStrategy#apply(de.ugoe.cs.cpdp.versions.SoftwareVersion,
     *      org.apache.commons.collections4.list.SetUniqueList)
     */
    @Override
    public void apply(SoftwareVersion testversion, SetUniqueList<SoftwareVersion> trainversionSet) {
        for (SoftwareVersion trainversion : trainversionSet) {
            setBiasedWeights(trainversion);
        }
    }

    /**
     * Helper method that sets the weights for a given data set.
     * 
     * @param version
     *            softwareversion of the data set whose weights are set
     */
    private void setBiasedWeights(SoftwareVersion version) {
        Instances data = version.getInstances();
        final int classIndex = data.classIndex();

        final int[] counts = data.attributeStats(classIndex).nominalCounts;

        final double weightNegatives = ((1 - this.bias) * data.numInstances()) / counts[0];
        final double weightPositives = (this.bias * data.numInstances()) / counts[1];

        for (int i = 0; i < data.numInstances(); i++) {
            Instance instance = data.instance(i);
            if (instance.value(classIndex) == 0) {
                instance.setWeight(weightNegatives);
            }
            if (instance.value(classIndex) == 1) {
                instance.setWeight(weightPositives);
            }
        }
    }

}
