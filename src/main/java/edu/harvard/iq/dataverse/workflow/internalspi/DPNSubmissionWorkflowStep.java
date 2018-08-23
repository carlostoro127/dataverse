package edu.harvard.iq.dataverse.workflow.internalspi;

import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.users.ApiToken;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.export.DataCiteExporter;
import edu.harvard.iq.dataverse.export.ExportException;
import edu.harvard.iq.dataverse.export.ExportService;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.bagit.BagIt_Export;
import edu.harvard.iq.dataverse.workflow.WorkflowContext;
import edu.harvard.iq.dataverse.workflow.step.Failure;
import edu.harvard.iq.dataverse.workflow.step.WorkflowStep;
import edu.harvard.iq.dataverse.workflow.step.WorkflowStepResult;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigInteger;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.EJB;

import org.apache.commons.io.IOUtils;
import org.duracloud.client.ContentStore;
import org.duracloud.client.ContentStoreManager;
import org.duracloud.client.ContentStoreManagerImpl;
import org.duracloud.common.model.Credential;
import org.duracloud.error.ContentStoreException;

/**
 * A step that submits a BagIT bag of the newly published dataset version to DPN
 * via he Duracloud Vault API.
 * 
 * @author jimmyers
 */
public class DPNSubmissionWorkflowStep implements WorkflowStep {

    @EJB
    SettingsServiceBean settingsService;
    
    @EJB
    AuthenticationServiceBean authService;
    

    private static final Logger logger = Logger.getLogger(DPNSubmissionWorkflowStep.class.getName());
    private static final String DEFAULT_PORT = "443";
    private static final String DEFAULT_CONTEXT = "durastore";

    private final Map<String, String> params;

    public DPNSubmissionWorkflowStep(Map<String, String> paramSet) {
        params = new HashMap<>(paramSet);
    }

    @Override
    public WorkflowStepResult run(WorkflowContext context) {

        String port = params.containsKey("port") ? params.get("port") : DEFAULT_PORT;
        String dpnContext = params.containsKey("context") ? params.get("context") : DEFAULT_CONTEXT;
        ContentStoreManager storeManager = new ContentStoreManagerImpl(params.get("host"), port, dpnContext);
        Credential credential = new Credential(params.get("username"), params.get("password"));
        storeManager.login(credential);

        String spaceName = context.getDataset().getGlobalId().asString().replace(':', '-').replace('/', '-')
                .replace('.', '-').toLowerCase();

        ContentStore store;
        try {
            store = storeManager.getPrimaryContentStore();

            store.createSpace(spaceName);

            // Store file

            String fileName = spaceName + ".v" + context.getNextVersionNumber() + "." + context.getNextMinorVersionNumber()
                    + ".zip";
            try {
                
                //Add BagIt ZIP file
                MessageDigest messageDigest = MessageDigest.getInstance("MD5");

               
                AuthenticatedUser user = context.getRequest().getAuthenticatedUser();
                ApiToken token = authService.findApiTokenByUser(user);
                if((token == null)|| (token.getExpireTime().before(new Date()))){
                    token = authService.generateApiTokenForUser(user);
                }
                final ApiToken finalToken = token;
                
            
                PipedInputStream in = new PipedInputStream();
                PipedOutputStream out = new PipedOutputStream(in);
                new Thread(
                  new Runnable(){
                    public void run(){
                        try {
                            BagIt_Export.exportDatasetVersionAsBag(context.getDataset().getReleasedVersion(), finalToken, settingsService, out);
                        } catch (Exception e) {
                            logger.severe("Error creating bag: " + e.getMessage());
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                            IOUtils.closeQuietly(in);
                            IOUtils.closeQuietly(out);
                        }
                    }
                  }
                ).start();
                
                DigestInputStream digestInputStream = new DigestInputStream(
                        in,
                        messageDigest);
                
                String checksum = store.addContent(spaceName, fileName, digestInputStream, -1l, null, null, null);
                logger.info("Content: " + fileName + " added with checksum: " + checksum);
                String localchecksum = new BigInteger(1, digestInputStream.getMessageDigest().digest()).toString(16);
                if (!checksum.equals(localchecksum)) {
                    logger.severe(checksum + " not equal to " + localchecksum);
                    return new Failure("Error in transferring Zip file to DPN",
                            "DPN Submission Failure: incomplete archive transfer");
                }

                //Add datacite.xml file
                messageDigest = MessageDigest.getInstance("MD5");

                digestInputStream = new DigestInputStream(
                        ExportService.getInstance(settingsService).getExport(context.getDataset(), DataCiteExporter.NAME),
                        messageDigest);

                checksum = store.addContent(spaceName, "datacite.xml", digestInputStream, -1l, null, null, null);
                logger.info("Content: datacite.xml added with checksum: " + checksum);
                localchecksum = new BigInteger(1, digestInputStream.getMessageDigest().digest()).toString(16);
                if (!checksum.equals(localchecksum)) {
                    logger.severe(checksum + " not equal to " + localchecksum);
                    return new Failure("Error in transferring DataCite.xml file to DPN",
                            "DPN Submission Failure: incomplete metadata transfer");
                }
                
                logger.info("DPN Submission step: Content Transferred");
                logger.log(Level.FINE, "Submitted {0} to DPN", spaceName);
                logger.log(Level.FINE, "Dataset id:{0}", context.getDataset().getId());
                logger.log(Level.FINE, "Trigger Type {0}", context.getType());
                logger.log(Level.FINE, "Next version:{0}.{1} isMinor:{2}",
                        new Object[] { context.getNextVersionNumber(), context.getNextMinorVersionNumber(),
                                context.isMinorRelease() });
                // Document location of dataset version replica (actually the URL where you can view it as an admin)
                StringBuffer sb = new StringBuffer("https://");
                sb.append(params.get("host"));
                if (!port.equals("443")) {
                    sb.append(":" + port);
                }
                sb.append("/duradmin/spaces/sm/");
                sb.append(store.getStoreId());
                sb.append("/" + spaceName + "/" + fileName);
                context.getDataset().getReleasedVersion().setReplicaLocation(sb.toString());
                logger.info("DPN Submission step complete: " + sb.toString());
            } catch (ContentStoreException | ExportException | IOException e) {
                // TODO Auto-generated catch block
                logger.warning(e.getMessage());
                e.printStackTrace();
                return new Failure("Error in transferring file to DPN",
                        "DPN Submission Failure: archive file not transferred");
            } catch (NoSuchAlgorithmException e) {
                logger.severe("MD5 MessageDigest not available!");
            }
        } catch (ContentStoreException e) {
            // TODO Auto-generated catch block
            logger.warning(e.getMessage());
            e.printStackTrace();
            String mesg = "DPN Submission Failure";
            if (!(context.getNextVersionNumber() == 1) || !(context.getNextMinorVersionNumber() == 0)) {
                mesg = mesg + ": Prior Version archiving not yet complete?";
            }
            return new Failure("Unable to create DPN space with name: " + spaceName, mesg);
        }

        return WorkflowStepResult.OK;
    }

    @Override
    public WorkflowStepResult resume(WorkflowContext context, Map<String, String> internalData, String externalData) {
        throw new UnsupportedOperationException("Not supported yet."); // This class does not need to resume.
    }

    @Override
    public void rollback(WorkflowContext context, Failure reason) {
        logger.log(Level.INFO, "rolling back workflow invocation {0}", context.getInvocationId());
        logger.warning("Manual cleanup of DPN Space: " + context.getDataset().getGlobalId().asString().replace(':', '-')
                .replace('/', '-').replace('.', '-').toLowerCase() + " may be required");
    }
}
