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
import java.util.List;

import de.lmu.ifi.dbs.elki.algorithm.clustering.DBSCAN;
import de.lmu.ifi.dbs.elki.data.Cluster;
import de.lmu.ifi.dbs.elki.data.Clustering;
import de.lmu.ifi.dbs.elki.data.DoubleVector;
import de.lmu.ifi.dbs.elki.data.model.Model;
import de.lmu.ifi.dbs.elki.data.type.TypeUtil;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.StaticArrayDatabase;
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection;
import de.lmu.ifi.dbs.elki.datasource.DatabaseConnection;
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.EuclideanDistanceFunction;
import de.ugoe.cs.cpdp.versions.SoftwareVersion;
import weka.core.Instances;

/**
 * DBSCAN filter after Kawata et al. (2015)
 * 
 * @author Steffen Herbold
 */
public class DBSCANFilter implements IPointWiseDataselectionStrategy {

    /*
     * (non-Javadoc)
     * 
     * @see de.ugoe.cs.cpdp.IParameterizable#setParameter(java.lang.String)
     */
    @Override
    public void setParameter(String parameters) {
        // do nothing
    }

    /**
     * @see IPointWiseDataselectionStrategy#apply(de.ugoe.cs.cpdp.versions.SoftwareVersion,
     *      de.ugoe.cs.cpdp.versions.SoftwareVersion)
     */
    @Override
    public SoftwareVersion apply(SoftwareVersion testversion, SoftwareVersion trainversion) {
        Instances traindata = trainversion.getInstances();
        Instances testdata = testversion.getInstances();
        Instances filteredTraindata = new Instances(traindata);
        Instances bugMatrix = null;
        List<Double> efforts = null;
        List<Double> numBugs = null;
        if (trainversion.getEfforts() != null) {
            efforts = new ArrayList<>();
        }
        if (trainversion.getNumBugs() != null) {
            numBugs = new ArrayList<>();
        }
        filteredTraindata.clear();
        if (trainversion.getBugMatrix() != null) {
            bugMatrix = new Instances(trainversion.getBugMatrix());
            bugMatrix.clear();
        }

        double[][] data =
            new double[testdata.size() + traindata.size()][testdata.numAttributes() - 1];
        int classIndex = testdata.classIndex();
        for (int i = 0; i < testdata.size(); i++) {
            int k = 0;
            for (int j = 0; j < testdata.numAttributes(); j++) {
                if (j != classIndex) {
                    data[i][k] = testdata.get(i).value(j);
                    k++;
                }
            }
        }
        for (int i = 0; i < traindata.size(); i++) {
            int k = 0;
            for (int j = 0; j < traindata.numAttributes(); j++) {
                if (j != classIndex) {
                    data[i + testdata.size()][k] = traindata.get(i).value(j);
                    k++;
                }
            }
        }
        DatabaseConnection dbc = new ArrayAdapterDatabaseConnection(data);
        Database db = new StaticArrayDatabase(dbc, null);
        db.initialize();
        DBSCAN<DoubleVector> dbscan =
            new DBSCAN<>(EuclideanDistanceFunction.STATIC, 1.0, 10);
        Clustering<Model> clusterer = dbscan.run(db);
        Relation<DoubleVector> rel = db.getRelation(TypeUtil.DOUBLE_VECTOR_FIELD);
        int firstInternalIndex = rel.iterDBIDs().internalGetIndex();

        for (Cluster<Model> cluster : clusterer.getAllClusters()) {
            // check if cluster has any training data
            DBIDIter iter = rel.iterDBIDs();
            boolean noMatch = true;
            for (int i = 0; noMatch && i < testdata.size(); i++) {
                noMatch = !cluster.getIDs().contains(iter);
                iter.advance();
            }
            if (!noMatch) {
                // cluster contains test data
                for (DBIDIter clusterIter = cluster.getIDs().iter(); clusterIter
                    .valid(); clusterIter.advance())
                {
                    int internalIndex =
                        clusterIter.internalGetIndex() - testdata.size() - firstInternalIndex;
                    if (internalIndex >= 0) {
                        // index belongs to a training instance
                        filteredTraindata.add(traindata.get(internalIndex));
                        if (bugMatrix != null) {
                            bugMatrix.add(trainversion.getBugMatrix().instance(internalIndex));
                        }
                        if (efforts != null) {
                            efforts.add(trainversion.getEfforts().get(internalIndex));
                        }
                        if (numBugs != null) {
                            numBugs.add(trainversion.getNumBugs().get(internalIndex));
                        }
                    }
                }

            }
        }

        return new SoftwareVersion(trainversion.getDataset(), trainversion.getProject(), trainversion.getVersion(),
                filteredTraindata, bugMatrix, efforts, numBugs, trainversion.getReleaseDate(), null);
    }

}
