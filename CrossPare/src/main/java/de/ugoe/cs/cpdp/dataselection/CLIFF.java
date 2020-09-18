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

package de.ugoe.cs.cpdp.dataselection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.ugoe.cs.cpdp.versions.SoftwareVersion;
import weka.core.Instances;

/**
 * Implements CLIFF data pruning.
 * 
 * @author Steffen Herbold
 */
public class CLIFF implements IPointWiseDataselectionStrategy {

    /**
     * percentage of data selected
     */
    private double percentage = 0.10;

    /**
     * number of ranges considered
     */
    private final int numRanges = 10;

    /**
     * Sets the number of neighbors.
     * 
     * @param parameters
     *            number of neighbors
     */
    @Override
    public void setParameter(String parameters) {
        if (parameters != null) {
            this.percentage = Double.parseDouble(parameters);
        }
    }

    /*
     * @see de.ugoe.cs.cpdp.dataselection.PointWiseDataselectionStrategy#apply(de.ugoe.cs.cpdp.versions.SoftwareVersion,
     * de.ugoe.cs.cpdp.versions.SoftwareVersion)
     */
    @Override
    public SoftwareVersion apply(SoftwareVersion testversion, SoftwareVersion trainversion) {
        return applyCLIFF(trainversion);
    }

    /**
     * <p>
     * Applies the CLIFF relevancy filter to the data.
     * </p>
     *
     * @param version
     *            version of the data
     * @return version of the CLIFF-filtered data
     */
    protected SoftwareVersion applyCLIFF(SoftwareVersion version) {
        Instances data = version.getInstances();
        Instances bugMatrix = null;
        List<Double> efforts = null;
        List<Double> numBugs = null;
        if (version.getEfforts() != null) {
            efforts = new ArrayList<>();
        }
        if (version.getNumBugs() != null) {
            numBugs = new ArrayList<>();
        }
        if (version.getBugMatrix() != null) {
            bugMatrix = new Instances(version.getBugMatrix());
            bugMatrix.clear();
        }
        final double[][] powerAttributes = new double[data.size()][data.numAttributes()];
        final double[] powerEntity = new double[data.size()];

        final int[] counts = data.attributeStats(data.classIndex()).nominalCounts;
        final double probDefect = data.numInstances() / (double) counts[1];

        for (int j = 0; j < data.numAttributes(); j++) {
            if (data.attribute(j) != data.classAttribute()) {
                final double[] ranges = getRanges(data, j);
                final double[] probDefectRange = getRangeProbabilities(data, j, ranges);

                for (int i = 0; i < data.numInstances(); i++) {
                    final double value = data.instance(i).value(j);
                    final int range = determineRange(ranges, value);
                    double probClass, probNotClass, probRangeClass, probRangeNotClass;
                    if (data.instance(i).classValue() == 1) {
                        probClass = probDefect;
                        probNotClass = 1.0 - probDefect;
                        probRangeClass = probDefectRange[range];
                        probRangeNotClass = 1.0 - probDefectRange[range];
                    }
                    else {
                        probClass = 1.0 - probDefect;
                        probNotClass = probDefect;
                        probRangeClass = 1.0 - probDefectRange[range];
                        probRangeNotClass = probDefectRange[range];
                    }
                    powerAttributes[i][j] = Math.pow(probRangeClass, 2.0) /
                        (probRangeClass * probClass + probRangeNotClass * probNotClass);
                }
            }
        }

        for (int i = 0; i < data.numInstances(); i++) {
            powerEntity[i] = 1.0;
            for (int j = 0; j < data.numAttributes(); j++) {
                powerEntity[i] *= powerAttributes[i][j];
            }
        }
        double[] sortedPower = powerEntity.clone();
        Arrays.sort(sortedPower);
        double cutOff = sortedPower[(int) (data.numInstances() * (1 - this.percentage))];

        Instances selected = new Instances(data);
        selected.delete();
        for (int i = 0; i < data.numInstances(); i++) {
            if (powerEntity[i] >= cutOff) {
                selected.add(data.instance(i));
                if (bugMatrix != null) {
                    bugMatrix.add(version.getBugMatrix().instance(i));
                }
                if (efforts != null) {
                    efforts.add(version.getEfforts().get(i));
                }
                if (numBugs != null) {
                    numBugs.add(version.getNumBugs().get(i));
                }
            }
        }
        return new SoftwareVersion(version.getDataset(), version.getProject(), version.getVersion(), selected,
                bugMatrix, efforts, numBugs, version.getReleaseDate(), null);
    }

    /**
     * <p>
     * Gets an array with the ranges from the data for a given attribute
     * </p>
     *
     * @param data
     *            the data
     * @param j
     *            index of the attribute
     * @return the ranges for the attribute
     */
    private double[] getRanges(Instances data, int j) {
        double[] values = new double[this.numRanges + 1];
        for (int k = 0; k < this.numRanges; k++) {
            values[k] = data.kthSmallestValue(j, (int) (data.size() * (k + 1.0) / this.numRanges));
        }
        values[this.numRanges] = data.attributeStats(j).numericStats.max;
        return values;
    }

    /**
     * <p>
     * Gets the probabilities of a positive prediction for each range for a given attribute
     * </p>
     *
     * @param data
     *            the data
     * @param j
     *            index of the attribute
     * @param ranges
     *            the ranges
     * @return probabilities for each range
     */
    private double[] getRangeProbabilities(Instances data, int j, double[] ranges) {
        double[] probDefectRange = new double[this.numRanges];
        int[] countRange = new int[this.numRanges];
        int[] countDefect = new int[this.numRanges];
        for (int i = 0; i < data.numInstances(); i++) {
            int range = determineRange(ranges, data.instance(i).value(j));
            countRange[range]++;
            if (data.instance(i).classValue() == 1) {
                countDefect[range]++;
            }

        }
        for (int k = 0; k < this.numRanges; k++) {
            probDefectRange[k] = ((double) countDefect[k]) / countRange[k];
        }
        return probDefectRange;
    }

    /**
     * <p>
     * Determines the range of a give value
     * </p>
     *
     * @param ranges
     *            the possible ranges
     * @param value
     *            the value
     * @return index of the range
     */
    private int determineRange(double[] ranges, double value) {
        for (int k = 0; k < this.numRanges; k++) {
            if (value <= ranges[k + 1]) {
                return k;
            }
        }
        throw new RuntimeException("invalid range or value");
    }
}
