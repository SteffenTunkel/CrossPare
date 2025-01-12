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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import java.util.Random;

import org.apache.commons.collections4.list.SetUniqueList;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.ugoe.cs.cpdp.versions.SoftwareVersion;
import weka.attributeSelection.SignificanceAttributeEval;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;

/**
 * Implements Heterogenous Defect Prediction after Nam et al. 2015.
 * 
 * We extend WekaBaseTraining because we have to Wrap the Classifier to use MetricMatching. This
 * also means we can use any Weka Classifier not just LogisticRegression.
 * 
 * Config: <setwisetestdataawaretrainer name="MetricMatchingTraining" param=
 * "Logistic weka.classifiers.functions.Logistic" threshold="0.05" method="spearman"/> Instead of
 * spearman metchod it also takes ks, percentile. Instead of Logistic every other weka classifier
 * can be chosen.
 * 
 * Future work: implement chisquare test in addition to significance for attribute selection
 * http://commons.apache.org/proper/commons-math/apidocs/org/apache/commons/math3/stat/inference/
 * ChiSquareTest.html use chiSquareTestDataSetsComparison
 */
@SuppressWarnings("hiding")
public class MetricMatchingTraining extends WekaBaseTraining
    implements ISetWiseTestdataAwareTrainingStrategy
{

	/**
     * Reference to the logger
     */
    private static final Logger LOGGER = LogManager.getLogger("main");
	
    private MetricMatch mm = null;
    private Classifier classifier = null;

    private String method;
    private float threshold;

    /**
     * We wrap the classifier here because of classifyInstance with our MetricMatchingClassfier
     * 
     * @return the classifier
     */
    @Override
    public Classifier getClassifier() {
        return this.classifier;
    }

    /**
     * Set similarity measure method.
     */
    @SuppressWarnings("hiding")
    @Override
    public void setMethod(String method) {
        this.method = method;
    }

    /**
     * Set threshold for similarity measure.
     */
    @SuppressWarnings("hiding")
    @Override
    public void setThreshold(String threshold) {
        this.threshold = Float.parseFloat(threshold);
    }

    /**
     * We need the test data instances to do a metric matching, so in this special case we get this
     * data before evaluation.
     */
    @SuppressWarnings("boxing")
    @Override
    public void apply(SetUniqueList<SoftwareVersion> trainversionSet, SoftwareVersion testversion) {
        // reset these for each run
        this.mm = null;
        this.classifier = null;

        double score = 0; // matching score to select the best matching training data from the set
        int num = 0;
        int biggest_num = 0;
        MetricMatch tmp;
        for (SoftwareVersion trainversion : trainversionSet) {
            num++;

            tmp = new MetricMatch(trainversion.getInstances(), testversion.getInstances());

            // metric selection may create error, continue to next training set
            try {
                tmp.attributeSelection();
                tmp.matchAttributes(this.method, this.threshold);
            }
            catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            // we only select the training data from our set with the most matching attributes
            if (tmp.getScore() > score && tmp.attributes.size() > 0) {
                score = tmp.getScore();
                this.mm = tmp;
                biggest_num = num;
            }
        }

        // if we have found a matching instance we use it, log information about the match for
        // additional eval later
        Instances ilist = null;
        if (this.mm != null) {
            ilist = this.mm.getMatchedTrain();
            LOGGER.debug("[MATCH FOUND] match: [" + biggest_num + "], score: [" +
                score + "], instances: [" + ilist.size() + "], attributes: [" +
                this.mm.attributes.size() + "], ilist attrs: [" + ilist.numAttributes() + "]");
            for (Map.Entry<Integer, Integer> attmatch : this.mm.attributes.entrySet()) {
                LOGGER.debug("[MATCHED ATTRIBUTE] source attribute: [" +
                    this.mm.train.attribute(attmatch.getKey()).name() + "], target attribute: [" +
                    this.mm.test.attribute(attmatch.getValue()).name() + "]");
            }
        }
        else {
            LOGGER.debug("[NO MATCH FOUND]");
        }

        // if we have a match we build the MetricMatchingClassifier, if not we fall back to FixClass
        // Classifier
        try {
            if (this.mm != null) {
                this.classifier = new MetricMatchingClassifier();
                this.classifier.buildClassifier(ilist);
                ((MetricMatchingClassifier) this.classifier).setMetricMatching(this.mm);
            }
            else {
                this.classifier = new FixClass();
                this.classifier.buildClassifier(ilist); // this is null, but the FixClass Classifier
                                                        // does not use it anyway
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Encapsulates the classifier configured with WekaBase within but use metric matching. This
     * allows us to use any Weka classifier with Heterogenous Defect Prediction.
     */
    public class MetricMatchingClassifier extends AbstractClassifier {

        private static final long serialVersionUID = -1342172153473770935L;
        private MetricMatch mm;
        private Classifier classifier;

        @Override
        public void buildClassifier(Instances traindata) throws Exception {
            this.classifier = setupClassifier();
            this.classifier.buildClassifier(traindata);
        }

        /**
         * Sets the MetricMatch instance so that we can use matched test data later.
         * 
         * @param mm
         *            the metric matching instance
         */
        @SuppressWarnings("hiding")
        public void setMetricMatching(MetricMatch mm) {
            this.mm = mm;
        }

        /**
         * Here we can not do the metric matching because we only get one instance. Therefore we
         * need a MetricMatch instance beforehand to use here.
         */
        @Override
        public double classifyInstance(Instance testdata) {
            // get a copy of testdata Instance with only the matched attributes
            Instance ntest = this.mm.getMatchedTestInstance(testdata);

            double ret = 0.0;
            try {
                ret = this.classifier.classifyInstance(ntest);
            }
            catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            return ret;
        }
    }

    /**
     * Encapsulates one MetricMatching process. One source (train) matches against one target
     * (test).
     */
    public class MetricMatch {
        Instances train;
        Instances test;

        // used to sum up the matching values of all attributes
        protected double p_sum = 0;

        // attribute matching, train -> test
        HashMap<Integer, Integer> attributes = new HashMap<>();

        // used for similarity tests
        protected ArrayList<double[]> train_values;
        protected ArrayList<double[]> test_values;

        /**
         * <p>
         * Matches the metrics between two sets
         * </p>
         *
         * @param train
         *            training data
         * @param test
         *            test data
         */
        @SuppressWarnings("hiding")
        public MetricMatch(Instances train, Instances test) {
            // this is expensive but we need to keep the original data intact
            this.train = this.deepCopy(train);
            this.test = test; // we do not need a copy here because we do not drop attributes before
                              // the matching and after the matching we create a new Instances with
                              // only the matched attributes

            // convert metrics of testdata and traindata to later use in similarity tests
            this.train_values = new ArrayList<>();
            for (int i = 0; i < this.train.numAttributes(); i++) {
                if (this.train.classIndex() != i) {
                    this.train_values.add(this.train.attributeToDoubleArray(i));
                }
            }

            this.test_values = new ArrayList<>();
            for (int i = 0; i < this.test.numAttributes(); i++) {
                if (this.test.classIndex() != i) {
                    this.test_values.add(this.test.attributeToDoubleArray(i));
                }
            }
        }

        /**
         * We have a lot of matching possibilities. Here we try to determine the best one.
         * 
         * @return double matching score
         */
        public double getScore() {
            int as = this.attributes.size(); // # of attributes that were matched

            // we use thresholding ranking approach for numInstances to influence the matching score
            int instances = this.train.numInstances();
            int inst_rank = 0;
            if (instances > 100) {
                inst_rank = 1;
            }
            if (instances > 500) {
                inst_rank = 2;
            }

            return this.p_sum + as + inst_rank;
        }

        /**
         * <p>
         * Returns the matches
         * </p>
         *
         * @return map with the metric matches
         */
        public HashMap<Integer, Integer> getAttributes() {
            return this.attributes;
        }

        /**
         * <p>
         * Returns the number of instances
         * </p>
         *
         * @return number of instances
         */
        public int getNumInstances() {
            return this.train_values.get(0).length;
        }

        /**
         * The test instance must be of the same dataset as the train data, otherwise WekaEvaluation
         * will die. This means we have to force the dataset of this.train (after matching) and only
         * set the values for the attributes we matched but with the index of the traindata
         * attributes we matched.
         * 
         * @param testInstance
         *            instance that is matched
         * @return the match
         */
        @SuppressWarnings("boxing")
        public Instance getMatchedTestInstance(Instance testInstance) {
            Instance ni = new DenseInstance(this.attributes.size() + 1);

            Instances inst = this.getMatchedTrain();

            ni.setDataset(inst);

            // assign only the matched attributes to new indexes
            double val;
            int k = 0;
            for (Map.Entry<Integer, Integer> attmatch : this.attributes.entrySet()) {
                // get value from matched attribute
                val = testInstance.value(attmatch.getValue());

                // set it to new index, the order of the attributes is the same
                ni.setValue(k, val);
                k++;
            }
            ni.setClassValue(testInstance.value(testInstance.classAttribute()));

            return ni;
        }

        /**
         * returns a new instances array with the metric matched training data
         * 
         * @return instances
         */
        public Instances getMatchedTrain() {
            return this.getMatchedInstances("train", this.train);
        }

        /**
         * returns a new instances array with the metric matched test data
         * 
         * @return instances
         */
        public Instances getMatchedTest() {
            return this.getMatchedInstances("test", this.test);
        }

        /**
         * We could drop unmatched attributes from our instances datasets. Alas, that would not be
         * nice for the following postprocessing jobs and would not work at all for evaluation. We
         * keep this as a warning for future generations.
         * 
         * @param name
         * @param data
         */
        @SuppressWarnings({ "unused", "boxing" })
        private void dropUnmatched(String name, Instances data) {
            for (int i = 0; i < data.numAttributes(); i++) {
                if (data.classIndex() == i) {
                    continue;
                }

                if (name.equals("train") && !this.attributes.containsKey(i)) {
                    data.deleteAttributeAt(i);
                }

                if (name.equals("test") && !this.attributes.containsValue(i)) {
                    data.deleteAttributeAt(i);
                }
            }
        }

        /**
         * Deep Copy (well, reasonably deep, not sure about header information of attributes) Weka
         * Instances.
         * 
         * @param data
         *            Instances
         * @return copy of Instances passed
         */
        private Instances deepCopy(Instances data) {
            Instances newInst = new Instances(data);

            newInst.clear();

            for (int i = 0; i < data.size(); i++) {
                Instance ni = new DenseInstance(data.numAttributes());
                for (int j = 0; j < data.numAttributes(); j++) {
                    ni.setValue(newInst.attribute(j), data.instance(i).value(data.attribute(j)));
                }
                newInst.add(ni);
            }

            return newInst;
        }

        /**
         * Returns a deep copy of passed Instances data for Train or Test data. It only keeps
         * attributes that have been matched.
         * 
         * @param name
         * @param data
         * @return matched Instances
         */
        @SuppressWarnings("boxing")
        private Instances getMatchedInstances(String name, Instances data) {
            ArrayList<Attribute> attrs = new ArrayList<>();

            // bug attr is a string, really!
            ArrayList<String> bug = new ArrayList<>();
            bug.add("0");
            bug.add("1");

            // add our matched attributes and last the bug
            for (Map.Entry<Integer, Integer> attmatch : this.attributes.entrySet()) {
                attrs.add(new Attribute(String.valueOf(attmatch.getValue())));
            }
            attrs.add(new Attribute("bug", bug));

            // create new instances object of the same size (at least for instances)
            Instances newInst = new Instances(name, attrs, data.size());

            // set last as class
            newInst.setClassIndex(newInst.numAttributes() - 1);

            // copy data for matched attributes, this depends if we return train or test data
            for (int i = 0; i < data.size(); i++) {
                Instance ni = new DenseInstance(this.attributes.size() + 1);

                int j = 0; // new indices!
                for (Map.Entry<Integer, Integer> attmatch : this.attributes.entrySet()) {

                    // test attribute match
                    int value = attmatch.getValue();

                    // train attribute match
                    if (name.equals("train")) {
                        value = attmatch.getKey();
                    }

                    ni.setValue(newInst.attribute(j), data.instance(i).value(value));
                    j++;
                }
                ni.setValue(ni.numAttributes() - 1, data.instance(i).value(data.classAttribute()));
                newInst.add(ni);
            }

            return newInst;
        }

        /**
         * performs the attribute selection we perform attribute significance tests and drop
         * attributes
         * 
         * attribute selection is only performed on the source dataset we retain the top 15%
         * attributes (if 15% is a float we just use the integer part)
         * 
         * @throws Exception
         *             quick and dirty exception forwarding
         */
        public void attributeSelection() throws Exception {

            // it is a wrapper, we may decide to implement ChiSquare or other means of selecting
            // attributes
            this.attributeSelectionBySignificance(this.train);
        }

        @SuppressWarnings("boxing")
        private void attributeSelectionBySignificance(Instances which) throws Exception {
            // Uses:
            // http://weka.sourceforge.net/doc.packages/probabilisticSignificanceAE/weka/attributeSelection/SignificanceAttributeEval.html
            SignificanceAttributeEval et = new SignificanceAttributeEval();
            et.buildEvaluator(which);

            // evaluate all training attributes
            HashMap<String, Double> saeval = new HashMap<>();
            for (int i = 0; i < which.numAttributes(); i++) {
                if (which.classIndex() != i) {
                    saeval.put(which.attribute(i).name(), et.evaluateAttribute(i));
                }
            }

            // sort by significance
            HashMap<String, Double> sorted = sortByValues(saeval);

            // Keep the best 15%
            double last = (saeval.size() / 100.0) * 15.0;
            int drop_first = saeval.size() - (int) last;

            // drop attributes above last
            Iterator<Entry<String, Double>> it = sorted.entrySet().iterator();
            while (drop_first > 0) {
                Map.Entry<String, Double> pair = it.next();
                if (which.attribute(pair.getKey()).index() != which.classIndex()) {
                    which.deleteAttributeAt(which.attribute(pair.getKey()).index());
                }
                drop_first -= 1;
            }
        }

        /**
         * Helper method to sort a hashmap by its values.
         * 
         * @param map
         * @return sorted map
         */
        private HashMap<String, Double> sortByValues(HashMap<String, Double> map) {
            List<Map.Entry<String, Double>> list =
                new LinkedList<>(map.entrySet());

            Collections.sort(list, new Comparator<Map.Entry<String, Double>>() {
                @Override
                public int compare(Map.Entry<String, Double> o1, Map.Entry<String, Double> o2) {
                    return (o1.getValue()).compareTo(o2.getValue());
                }
            });

            HashMap<String, Double> sortedHashMap = new LinkedHashMap<>();
            for (Map.Entry<String, Double> item : list) {
                sortedHashMap.put(item.getKey(), item.getValue());
            }
            return sortedHashMap;
        }

        /**
         * Executes the similarity matching between train and test data.
         * 
         * After this function is finished we have this.attributes with the correct matching between
         * train and test data attributes.
         * 
         * @param type
         *            Type of matching. Current currently supports Spearman's rank correlation,
         *            Kolomogorov-Smirnoff tests, and percentile based matching.
         * @param cutoff
         *            cutoff for matching
         */
        @SuppressWarnings("boxing")
        public void matchAttributes(String type, double cutoff) {

            MWBMatchingAlgorithm mwbm =
                new MWBMatchingAlgorithm(this.train.numAttributes(), this.test.numAttributes());

            if (type.equals("spearman")) {
                this.spearmansRankCorrelation(cutoff, mwbm);
            }
            else if (type.equals("ks")) {
                this.kolmogorovSmirnovTest(cutoff, mwbm);
            }
            else if (type.equals("percentile")) {
                this.percentiles(cutoff, mwbm);
            }
            else {
                throw new RuntimeException("unknown matching method");
            }

            // resulting maximal match gets assigned to this.attributes
            int[] result = mwbm.getMatching();
            for (int i = 0; i < result.length; i++) {

                // -1 means that it is not in the set of maximal matching
                if (i != -1 && result[i] != -1) {
                    this.p_sum += mwbm.weights[i][result[i]]; // we add the weight of the returned
                                                              // matching for scoring the complete
                                                              // match later
                    this.attributes.put(i, result[i]);
                }
            }
        }

        /**
         * Calculates the Percentiles of the source and target metrics.
         * 
         * @param cutoff
         *            cutoff value for percentiles that are considered similar
         * @param mwbm
         *            matching strategy
         */
        public void percentiles(double cutoff, MWBMatchingAlgorithm mwbm) {
            for (int i = 0; i < this.train.numAttributes(); i++) {
                for (int j = 0; j < this.test.numAttributes(); j++) {
                    // negative infinity counts as not present, we do this so we don't have to map
                    // between attribute indexes in weka
                    // and the result of the mwbm computation
                    mwbm.setWeight(i, j, Double.NEGATIVE_INFINITY);

                    // class attributes are not relevant
                    if (this.test.classIndex() == j) {
                        continue;
                    }
                    if (this.train.classIndex() == i) {
                        continue;
                    }

                    // get percentiles
                    double trainvals[] = this.train_values.get(i);
                    double testvals[] = this.test_values.get(j);

                    Arrays.sort(trainvals);
                    Arrays.sort(testvals);

                    // percentiles
                    double train_p;
                    double test_p;
                    double score = 0.0;
                    for (int p = 1; p <= 9; p++) {
                        train_p = trainvals[(int) Math.ceil(trainvals.length * (p / 100))];
                        test_p = testvals[(int) Math.ceil(testvals.length * (p / 100))];

                        if (train_p > test_p) {
                            score += test_p / train_p;
                        }
                        else {
                            score += train_p / test_p;
                        }
                    }

                    if (score > cutoff) {
                        mwbm.setWeight(i, j, score);
                    }
                }
            }
        }

        /**
         * Calculate Spearmans rank correlation coefficient as matching score. The number of
         * instances for the source and target needs to be the same so we randomly sample from the
         * bigger one.
         * 
         * @param cutoff
         *            cutoff value for correlations that are considered similar
         * @param mwbm
         *            matching strategy
         */
        public void spearmansRankCorrelation(double cutoff, MWBMatchingAlgorithm mwbm) {
            double p = 0;

            SpearmansCorrelation t = new SpearmansCorrelation();

            // size has to be the same so we randomly sample the number of the smaller sample from
            // the big sample
            if (this.train.size() > this.test.size()) {
                this.sample(this.train, this.test, this.train_values);
            }
            else if (this.test.size() > this.train.size()) {
                this.sample(this.test, this.train, this.test_values);
            }

            // try out possible attribute combinations
            for (int i = 0; i < this.train.numAttributes(); i++) {
                for (int j = 0; j < this.test.numAttributes(); j++) {
                    // negative infinity counts as not present, we do this so we don't have to map
                    // between attribute indexs in weka
                    // and the result of the mwbm computation
                    mwbm.setWeight(i, j, Double.NEGATIVE_INFINITY);

                    // class attributes are not relevant
                    if (this.test.classIndex() == j) {
                        continue;
                    }
                    if (this.train.classIndex() == i) {
                        continue;
                    }

                    p = t.correlation(this.train_values.get(i), this.test_values.get(j));
                    if (p > cutoff) {
                        mwbm.setWeight(i, j, p);
                    }
                }
            }
        }

        /**
         * Helper method to sample instances for the Spearman rank correlation coefficient method.
         * 
         * @param bigger
         * @param smaller
         * @param values
         */
        @SuppressWarnings("boxing")
        private void sample(Instances bigger, Instances smaller, ArrayList<double[]> values) {
            // we want to at keep the indices we select the same
            int indices_to_draw = smaller.size();
            ArrayList<Integer> indices = new ArrayList<>();
            Random rand = new Random();
            while (indices_to_draw > 0) {

                int index = rand.nextInt(bigger.size() - 1);

                if (!indices.contains(index)) {
                    indices.add(index);
                    indices_to_draw--;
                }
            }

            // now reduce our values to the indices we choose above for every attribute
            for (int att = 0; att < bigger.numAttributes() - 1; att++) {

                // get double for the att
                double[] vals = values.get(att);
                double[] new_vals = new double[indices.size()];

                int i = 0;
                for (Iterator<Integer> it = indices.iterator(); it.hasNext();) {
                    new_vals[i] = vals[it.next()];
                    i++;
                }

                values.set(att, new_vals);
            }
        }

        /**
         * We run the kolmogorov-smirnov test on the data from our test an traindata if the p value
         * is above the cutoff we include it in the results p value tends to be 0 when the
         * distributions of the data are significantly different but we want them to be the same
         * 
         * @param cutoff
         *            cutoff value for p-values that are considered similar
         * @param mwbm
         *            matching strategy
         */
        public void kolmogorovSmirnovTest(double cutoff, MWBMatchingAlgorithm mwbm) {
            double p = 0;

            KolmogorovSmirnovTest t = new KolmogorovSmirnovTest();
            for (int i = 0; i < this.train.numAttributes(); i++) {
                for (int j = 0; j < this.test.numAttributes(); j++) {
                    // negative infinity counts as not present, we do this so we don't have to map
                    // between attribute indexs in weka
                    // and the result of the mwbm computation
                    mwbm.setWeight(i, j, Double.NEGATIVE_INFINITY);

                    // class attributes are not relevant
                    if (this.test.classIndex() == j) {
                        continue;
                    }
                    if (this.train.classIndex() == i) {
                        continue;
                    }

                    // this may invoke exactP on small sample sizes which will not terminate in all
                    // cases
                    // p = t.kolmogorovSmirnovTest(this.train_values.get(i),
                    // this.test_values.get(j), false);

                    // this uses approximateP everytime
                    p = t.approximateP(
                                       t.kolmogorovSmirnovStatistic(this.train_values.get(i),
                                                                    this.test_values.get(j)),
                                       this.train_values.get(i).length,
                                       this.test_values.get(j).length);
                    if (p > cutoff) {
                        mwbm.setWeight(i, j, p);
                    }
                }
            }
        }
    }

    /*
     * Copyright (c) 2007, Massachusetts Institute of Technology Copyright (c) 2005-2006, Regents of
     * the University of California All rights reserved.
     * 
     * Redistribution and use in source and binary forms, with or without modification, are
     * permitted provided that the following conditions are met:
     *
     * * Redistributions of source code must retain the above copyright notice, this list of
     * conditions and the following disclaimer.
     *
     * * Redistributions in binary form must reproduce the above copyright notice, this list of
     * conditions and the following disclaimer in the documentation and/or other materials provided
     * with the distribution.
     *
     * * Neither the name of the University of California, Berkeley nor the names of its
     * contributors may be used to endorse or promote products derived from this software without
     * specific prior written permission.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
     * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
     * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
     * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
     * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
     * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
     * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
     * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
     * OF THE POSSIBILITY OF SUCH DAMAGE.
     */

    /**
     * An engine for finding the maximum-weight matching in a complete bipartite graph. Suppose we
     * have two sets <i>S</i> and <i>T</i>, both of size <i>n</i>. For each <i>i</i> in <i>S</i> and
     * <i>j</i> in <i>T</i>, we have a weight <i>w<sub>ij</sub></i>. A perfect matching <i>X</i> is
     * a subset of <i>S</i> x <i>T</i> such that each <i>i</i> in <i>S</i> occurs in exactly one
     * element of <i>X</i>, and each <i>j</i> in <i>T</i> occurs in exactly one element of <i>X</i>.
     * Thus, <i>X</i> can be thought of as a one-to-one function from <i>S</i> to <i>T</i>. The
     * weight of <i>X</i> is the sum, over (<i>i</i>, <i>j</i>) in <i>X</i>, of <i>w
     * <sub>ij</sub></i>. A BipartiteMatcher takes the number <i>n</i> and the weights <i>w
     * <sub>ij</sub></i>, and finds a perfect matching of maximum weight.
     *
     * It uses the Hungarian algorithm of Kuhn (1955), as improved and presented by E. L. Lawler in
     * his book <cite>Combinatorial Optimization: Networks and Matroids</cite> (Holt, Rinehart and
     * Winston, 1976, p. 205-206). The running time is O(<i>n</i><sup>3</sup>). The weights can be
     * any finite real numbers; Lawler's algorithm assumes positive weights, so if necessary we add
     * a constant <i>c</i> to all the weights before running the algorithm. This increases the
     * weight of every perfect matching by <i>nc</i>, which doesn't change which perfect matchings
     * have maximum weight.
     *
     * If a weight is set to Double.NEGATIVE_INFINITY, then the algorithm will behave as if that
     * edge were not in the graph. If all the edges incident on a given node have weight
     * Double.NEGATIVE_INFINITY, then the final result will not be a perfect matching, and an
     * exception will be thrown.
     */
    class MWBMatchingAlgorithm {
        /**
         * Creates a BipartiteMatcher without specifying the graph size. Calling any other method
         * before calling reset will yield an IllegalStateException.
         */

        /**
         * Tolerance for comparisons to zero, to account for floating-point imprecision. We consider
         * a positive number to be essentially zero if it is strictly less than TOL.
         */
        private static final double TOL = 1e-10;
        // Number of left side nodes
        int n;

        // Number of right side nodes
        int m;

        double[][] weights;
        double minWeight;
        double maxWeight;

        // If (i, j) is in the mapping, then sMatches[i] = j and tMatches[j] = i.
        // If i is unmatched, then sMatches[i] = -1 (and likewise for tMatches).
        int[] sMatches;
        int[] tMatches;

        static final int NO_LABEL = -1;
        static final int EMPTY_LABEL = -2;

        int[] sLabels;
        int[] tLabels;

        double[] u;
        double[] v;

        double[] pi;

        List<Integer> eligibleS = new ArrayList<>();
        List<Integer> eligibleT = new ArrayList<>();

        public MWBMatchingAlgorithm() {
            this.n = -1;
            this.m = -1;
        }

        /**
         * Creates a BipartiteMatcher and prepares it to run on an n x m graph. All the weights are
         * initially set to 1.
         * 
         * @param n
         *            size of the graph
         * @param m
         *            size of the graph
         */
        @SuppressWarnings("hiding")
        public MWBMatchingAlgorithm(int n, int m) {
            reset(n, m);
        }

        /**
         * Resets the BipartiteMatcher to run on an n x m graph. The weights are all reset to 1.
         */
        @SuppressWarnings("hiding")
        private void reset(int n, int m) {
            if (n < 0 || m < 0) {
                throw new IllegalArgumentException("Negative num nodes: " + n + " or " + m);
            }
            this.n = n;
            this.m = m;

            this.weights = new double[n][m];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    this.weights[i][j] = 1;
                }
            }
            this.minWeight = 1;
            this.maxWeight = Double.NEGATIVE_INFINITY;

            this.sMatches = new int[n];
            this.tMatches = new int[m];
            this.sLabels = new int[n];
            this.tLabels = new int[m];
            this.u = new double[n];
            this.v = new double[m];
            this.pi = new double[m];

        }

        /**
         * Sets the weight w<sub>ij</sub> to the given value w.
         * 
         * @param i
         *            index i
         * @param j
         *            index j
         * @param w
         *            the weight
         *
         * @throws IllegalArgumentException
         *             if i or j is outside the range [0, n).
         */
        public void setWeight(int i, int j, double w) {
            if (this.n == -1 || this.m == -1) {
                throw new IllegalStateException("Graph size not specified.");
            }
            if ((i < 0) || (i >= this.n)) {
                throw new IllegalArgumentException("i-value out of range: " + i);
            }
            if ((j < 0) || (j >= this.m)) {
                throw new IllegalArgumentException("j-value out of range: " + j);
            }
            if (Double.isNaN(w)) {
                throw new IllegalArgumentException("Illegal weight: " + w);
            }

            this.weights[i][j] = w;
            if ((w > Double.NEGATIVE_INFINITY) && (w < this.minWeight)) {
                this.minWeight = w;
            }
            if (w > this.maxWeight) {
                this.maxWeight = w;
            }
        }

        /**
         * Returns a maximum-weight perfect matching relative to the weights specified with
         * setWeight. The matching is represented as an array arr of length n, where arr[i] = j if
         * (i,j) is in the matching.
         * 
         * @return the matchings
         */
        @SuppressWarnings("boxing")
        public int[] getMatching() {
            if (this.n == -1 || this.m == -1) {
                throw new IllegalStateException("Graph size not specified.");
            }
            if (this.n == 0) {
                return new int[0];
            }
            ensurePositiveWeights();

            // Step 0: Initialization
            this.eligibleS.clear();
            this.eligibleT.clear();
            for (Integer i = 0; i < this.n; i++) {
                this.sMatches[i] = -1;

                this.u[i] = this.maxWeight; // ambiguous on p. 205 of Lawler, but see p. 202

                // this is really first run of Step 1.0
                this.sLabels[i] = EMPTY_LABEL;
                this.eligibleS.add(i);
            }

            for (int j = 0; j < this.m; j++) {
                this.tMatches[j] = -1;

                this.v[j] = 0;
                this.pi[j] = Double.POSITIVE_INFINITY;

                // this is really first run of Step 1.0
                this.tLabels[j] = NO_LABEL;
            }

            while (true) {
                // Augment the matching until we can't augment any more given the
                // current settings of the dual variables.
                while (true) {
                    // Steps 1.1-1.4: Find an augmenting path
                    int lastNode = findAugmentingPath();
                    if (lastNode == -1) {
                        break; // no augmenting path
                    }

                    // Step 2: Augmentation
                    flipPath(lastNode);
                    for (int i = 0; i < this.n; i++)
                        this.sLabels[i] = NO_LABEL;

                    for (int j = 0; j < this.m; j++) {
                        this.pi[j] = Double.POSITIVE_INFINITY;
                        this.tLabels[j] = NO_LABEL;
                    }

                    // This is Step 1.0
                    this.eligibleS.clear();
                    for (int i = 0; i < this.n; i++) {
                        if (this.sMatches[i] == -1) {
                            this.sLabels[i] = EMPTY_LABEL;
                            this.eligibleS.add(i);
                        }
                    }

                    this.eligibleT.clear();
                }

                // Step 3: Change the dual variables

                // delta1 = min_i u[i]
                double delta1 = Double.POSITIVE_INFINITY;
                for (int i = 0; i < this.n; i++) {
                    if (this.u[i] < delta1) {
                        delta1 = this.u[i];
                    }
                }

                // delta2 = min_{j : pi[j] > 0} pi[j]
                double delta2 = Double.POSITIVE_INFINITY;
                for (int j = 0; j < this.m; j++) {
                    if ((this.pi[j] >= TOL) && (this.pi[j] < delta2)) {
                        delta2 = this.pi[j];
                    }
                }

                if (delta1 < delta2) {
                    // In order to make another pi[j] equal 0, we'd need to
                    // make some u[i] negative.
                    break; // we have a maximum-weight matching
                }

                changeDualVars(delta2);
            }

            int[] matching = new int[this.n];
            for (int i = 0; i < this.n; i++) {
                matching[i] = this.sMatches[i];
            }
            return matching;
        }

        /**
         * Tries to find an augmenting path containing only edges (i,j) for which u[i] + v[j] =
         * weights[i][j]. If it succeeds, returns the index of the last node in the path. Otherwise,
         * returns -1. In any case, updates the labels and pi values.
         */
        int findAugmentingPath() {
            while ((!this.eligibleS.isEmpty()) || (!this.eligibleT.isEmpty())) {
                if (!this.eligibleS.isEmpty()) {
                    int i = this.eligibleS.get(this.eligibleS.size() - 1).intValue();
                    this.eligibleS.remove(this.eligibleS.size() - 1);
                    for (int j = 0; j < this.m; j++) {
                        // If pi[j] has already been decreased essentially
                        // to zero, then j is already labeled, and we
                        // can't decrease pi[j] any more. Omitting the
                        // pi[j] >= TOL check could lead us to relabel j
                        // unnecessarily, since the diff we compute on the
                        // next line may end up being less than pi[j] due
                        // to floating point imprecision.
                        if ((this.tMatches[j] != i) && (this.pi[j] >= TOL)) {
                            double diff = this.u[i] + this.v[j] - this.weights[i][j];
                            if (diff < this.pi[j]) {
                                this.tLabels[j] = i;
                                this.pi[j] = diff;
                                if (this.pi[j] < TOL) {
                                    this.eligibleT.add(j);
                                }
                            }
                        }
                    }
                }
                else {
                    int j = this.eligibleT.get(this.eligibleT.size() - 1).intValue();
                    this.eligibleT.remove(this.eligibleT.size() - 1);
                    if (this.tMatches[j] == -1) {
                        return j; // we've found an augmenting path
                    }

                    int i = this.tMatches[j];
                    this.sLabels[i] = j;
                    this.eligibleS.add(i); // ok to add twice
                }
            }

            return -1;
        }

        /**
         * Given an augmenting path ending at lastNode, "flips" the path. This means that an edge on
         * the path is in the matching after the flip if and only if it was not in the matching
         * before the flip. An augmenting path connects two unmatched nodes, so the result is still
         * a matching.
         */
        void flipPath(int lastNode) {
            int myLastNode = lastNode;
            while (myLastNode != EMPTY_LABEL) {
                int parent = this.tLabels[myLastNode];

                // Add (parent, lastNode) to matching. We don't need to
                // explicitly remove any edges from the matching because:
                // * We know at this point that there is no i such that
                // sMatches[i] = lastNode.
                // * Although there might be some j such that tMatches[j] =
                // parent, that j must be sLabels[parent], and will change
                // tMatches[j] in the next time through this loop.
                this.sMatches[parent] = lastNode;
                this.tMatches[myLastNode] = parent;

                myLastNode = this.sLabels[parent];
            }
        }

        void changeDualVars(double delta) {
            for (int i = 0; i < this.n; i++) {
                if (this.sLabels[i] != NO_LABEL) {
                    this.u[i] -= delta;
                }
            }

            for (int j = 0; j < this.m; j++) {
                if (this.pi[j] < TOL) {
                    this.v[j] += delta;
                }
                else if (this.tLabels[j] != NO_LABEL) {
                    this.pi[j] -= delta;
                    if (this.pi[j] < TOL) {
                        this.eligibleT.add(j);
                    }
                }
            }
        }

        /**
         * Ensures that all weights are either Double.NEGATIVE_INFINITY, or strictly greater than
         * zero.
         */
        private void ensurePositiveWeights() {
            // minWeight is the minimum non-infinite weight
            if (this.minWeight < TOL) {
                for (int i = 0; i < this.n; i++) {
                    for (int j = 0; j < this.m; j++) {
                        this.weights[i][j] = this.weights[i][j] - this.minWeight + 1;
                    }
                }

                this.maxWeight = this.maxWeight - this.minWeight + 1;
                this.minWeight = 1;
            }
        }

        @SuppressWarnings("unused")
        private void printWeights() {
            for (int i = 0; i < this.n; i++) {
                for (int j = 0; j < this.m; j++) {
                    System.out.print(this.weights[i][j] + " ");
                }
                System.out.println("");
            }
        }
    }
}
