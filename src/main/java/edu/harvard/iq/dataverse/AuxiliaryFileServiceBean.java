
package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.dataaccess.StorageIO;
import edu.harvard.iq.dataverse.util.FileUtil;
import edu.harvard.iq.dataverse.util.SystemConfig;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.Response;

import org.apache.tika.Tika;

/**
 *
 * @author ekraffmiller
 *  Methods related to the AuxiliaryFile Entity.
 */
@Stateless
@Named
public class AuxiliaryFileServiceBean implements java.io.Serializable {
   private static final Logger logger = Logger.getLogger(AuxiliaryFileServiceBean.class.getCanonicalName());

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    private EntityManager em;
    
    @EJB
    private SystemConfig systemConfig;
    

    public AuxiliaryFile find(Object pk) {
        return em.find(AuxiliaryFile.class, pk);
    }

    public AuxiliaryFile save(AuxiliaryFile auxiliaryFile) {
        AuxiliaryFile savedFile = em.merge(auxiliaryFile);
        return savedFile;

    }
    
    /**
     * Save the physical file to storageIO, and save the AuxiliaryFile entity
     * to the database.  This should be an all or nothing transaction - if either
     * process fails, than nothing will be saved
     * @param fileInputStream - auxiliary file data to be saved
     * @param dataFile  - the dataFile entity this will be added to
     * @param formatTag - type of file being saved
     * @param formatVersion - to distinguish between multiple versions of a file
     * @param origin - name of the tool/system that created the file
     * @param isPublic boolean - is this file available to any user?
     * @return success boolean - returns whether the save was successful
     */
    public AuxiliaryFile processAuxiliaryFile(InputStream fileInputStream, DataFile dataFile, String formatTag, String formatVersion, String origin, boolean isPublic) {

        StorageIO<DataFile> storageIO = null;
        AuxiliaryFile auxFile = new AuxiliaryFile();
        String auxExtension = formatTag + "_" + formatVersion;
        try {
            // Save to storage first.
            // If that is successful (does not throw exception),
            // then save to db.
            // If the db fails for any reason, then rollback
            // by removing the auxfile from storage.
            storageIO = dataFile.getStorageIO();
            if (storageIO.isAuxObjectCached(auxExtension)) {
                throw new ClientErrorException("Auxiliary file already exists", Response.Status.CONFLICT);
            }
            MessageDigest md;
            try {
                md = MessageDigest.getInstance(systemConfig.getFileFixityChecksumAlgorithm().toString());
            } catch (NoSuchAlgorithmException e) {
                logger.severe("NoSuchAlgorithmException for system fixity algorithm: " + systemConfig.getFileFixityChecksumAlgorithm().toString());
                throw new InternalServerErrorException();
            }
            DigestInputStream di = new DigestInputStream(fileInputStream, md);

            storageIO.saveInputStreamAsAux(fileInputStream, auxExtension);
            auxFile.setChecksum(FileUtil.checksumDigestToString(di.getMessageDigest().digest()));

            Tika tika = new Tika();
            auxFile.setContentType(tika.detect(storageIO.getAuxFileAsInputStream(auxExtension)));
            auxFile.setFormatTag(formatTag);
            auxFile.setFormatVersion(formatVersion);
            auxFile.setOrigin(origin);
            auxFile.setIsPublic(isPublic);
            auxFile.setDataFile(dataFile);
            auxFile.setFileSize(storageIO.getAuxObjectSize(auxExtension));
            auxFile = save(auxFile);
        } catch (IOException ioex) {
            logger.severe("IO Exception trying to save auxiliary file: " + ioex.getMessage());
            throw new InternalServerErrorException();
        } catch (RuntimeException e) {
            // If anything fails during database insert, remove file from storage
            try {
                storageIO.deleteAuxObject(auxExtension);
            } catch (IOException ioex) {
                logger.warning("IO Exception trying remove auxiliary file in exception handler: " + ioex.getMessage());
            }
            throw e;
        }
        return auxFile;
    }
    
    public AuxiliaryFile lookupAuxiliaryFile(DataFile dataFile, String formatTag, String formatVersion) {
        
        Query query = em.createQuery("select object(o) from AuxiliaryFile as o where o.dataFile.id = :dataFileId and o.formatTag = :formatTag and o.formatVersion = :formatVersion");
                
        query.setParameter("dataFileId", dataFile.getId());
        query.setParameter("formatTag", formatTag);
        query.setParameter("formatVersion", formatVersion);
        try {
            AuxiliaryFile retVal = (AuxiliaryFile)query.getSingleResult();
            return retVal;
        } catch(Exception ex) {
            return null;
        }
    }
    
    @SuppressWarnings("unchecked")
    public List<AuxiliaryFile> listAuxiliaryFiles(DataFile dataFile, String origin) {
        
        Query query = em.createQuery("select object(o) from AuxiliaryFile as o where o.dataFile.id = :dataFileId and o.origin = :origin");
                
        query.setParameter("dataFileId", dataFile.getId());
        query.setParameter("origin", origin);
        try {
            List<AuxiliaryFile> retVal = (List<AuxiliaryFile>)query.getResultList();
            return retVal;
        } catch(Exception ex) {
            return null;
        }
    }

    public void deleteAuxiliaryFile(DataFile dataFile, String formatTag, String formatVersion) throws IOException {
        AuxiliaryFile af = lookupAuxiliaryFile(dataFile, formatTag, formatVersion);
        if (af == null) {
            throw new FileNotFoundException();
        }
        em.remove(af);
        StorageIO<?> storageIO;
        storageIO = dataFile.getStorageIO();
        String auxExtension = formatTag + "_" + formatVersion;
        if (storageIO.isAuxObjectCached(auxExtension)) {
            storageIO.deleteAuxObject(auxExtension);
        }
    }

}
