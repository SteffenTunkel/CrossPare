
package de.ugoe.cs.cpdp.dataprocessing;

import org.apache.commons.collections4.list.SetUniqueList;

import de.ugoe.cs.cpdp.util.WekaUtils;
import de.ugoe.cs.cpdp.versions.SoftwareVersion;

/**
 * <p>
 * Makes the class attribute numeric, in case it was a nominal label before.
 * </p>
 * 
 * @author Steffen Herbold
 */
public class MakeClassNumeric implements ISetWiseProcessingStrategy, IProcessesingStrategy {

    /*
     * (non-Javadoc)
     * 
     * @see de.ugoe.cs.cpdp.IParameterizable#setParameter(java.lang.String)
     */
    @Override
    public void setParameter(String parameters) {
        // dummy, no parameters
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.ugoe.cs.cpdp.dataprocessing.IProcessesingStrategy#apply(de.ugoe.cs.cpdp.versions.SoftwareVersion,
     * de.ugoe.cs.cpdp.versions.SoftwareVersion)
     */
    @Override
    public void apply(SoftwareVersion testversion, SoftwareVersion trainversion) {
        WekaUtils.makeClassNumeric(testversion.getInstances());
        WekaUtils.makeClassNumeric(trainversion.getInstances());
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.ugoe.cs.cpdp.dataprocessing.ISetWiseProcessingStrategy#apply(de.ugoe.cs.cpdp.versions.SoftwareVersion,
     * org.apache.commons.collections4.list.SetUniqueList)
     */
    @Override
    public void apply(SoftwareVersion testversion, SetUniqueList<SoftwareVersion> trainversionSet) {
        WekaUtils.makeClassNumeric(testversion.getInstances());
        for (SoftwareVersion trainversion : trainversionSet) {
            WekaUtils.makeClassNumeric(trainversion.getInstances());
        }
    }

}
