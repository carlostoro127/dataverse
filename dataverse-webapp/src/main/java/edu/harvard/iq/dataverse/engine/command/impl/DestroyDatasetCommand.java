package edu.harvard.iq.dataverse.engine.command.impl;

import edu.harvard.iq.dataverse.engine.command.AbstractVoidCommand;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.RequiredPermissions;
import edu.harvard.iq.dataverse.engine.command.exception.PermissionException;
import edu.harvard.iq.dataverse.globalid.GlobalIdServiceBean;
import edu.harvard.iq.dataverse.persistence.datafile.DataFile;
import edu.harvard.iq.dataverse.persistence.dataset.Dataset;
import edu.harvard.iq.dataverse.persistence.dataverse.Dataverse;
import edu.harvard.iq.dataverse.persistence.user.AuthenticatedUser;
import edu.harvard.iq.dataverse.persistence.user.DataverseRole;
import edu.harvard.iq.dataverse.persistence.user.Permission;
import edu.harvard.iq.dataverse.persistence.user.RoleAssignment;
import edu.harvard.iq.dataverse.search.index.IndexResponse;
import edu.harvard.iq.dataverse.search.index.IndexServiceBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Same as {@link DeleteDatasetCommand}, but does not stop if the dataset is
 * published. This command is reserved for super-users, if at all.
 *
 * @author michael
 */
// Since this is used by DeleteDatasetCommand, must have at least that permission
// (for released, user is checked for superuser)
@RequiredPermissions(Permission.DeleteDatasetDraft)
public class DestroyDatasetCommand extends AbstractVoidCommand {

    private static final Logger logger = Logger.getLogger(DestroyDatasetCommand.class.getCanonicalName());

    private final Dataset doomed;

    public DestroyDatasetCommand(Dataset doomed, DataverseRequest aRequest) {
        super(aRequest, doomed);
        this.doomed = doomed;
    }

    @Override
    protected void executeImpl(CommandContext ctxt) {

        // first check if dataset is released, and if so, if user is a superuser
        if (doomed.isReleased() && (!(getUser() instanceof AuthenticatedUser) || !getUser().isSuperuser())) {
            throw new PermissionException("Destroy can only be called by superusers.",
                                          this, Collections.singleton(Permission.DeleteDatasetDraft), doomed);
        }

        // If there is a dedicated thumbnail DataFile, it needs to be reset
        // explicitly, or we'll get a constraint violation when deleting:
        doomed.setThumbnailFile(null);
        final Dataset managedDoomed = ctxt.em().merge(doomed);

        List<String> datasetAndFileSolrIdsToDelete = new ArrayList<>();
        // files need to iterate through and remove 'by hand' to avoid
        // optimistic lock issues... (plus the physical files need to be 
        // deleted too!)

        Iterator<DataFile> dfIt = doomed.getFiles().iterator();
        while (dfIt.hasNext()) {
            DataFile df = dfIt.next();
            // Gather potential Solr IDs of files. As of this writing deaccessioned files are never indexed.
            String solrIdOfPublishedFile = IndexServiceBean.solrDocIdentifierFile + df.getId();
            datasetAndFileSolrIdsToDelete.add(solrIdOfPublishedFile);
            String solrIdOfDraftFile = IndexServiceBean.solrDocIdentifierFile + df.getId() + IndexServiceBean.draftSuffix;
            datasetAndFileSolrIdsToDelete.add(solrIdOfDraftFile);
            ctxt.engine().submit(new DeleteDataFileCommand(df, getRequest(), true));
            dfIt.remove();
        }

        //also, lets delete the uploaded thumbnails!
        ctxt.datasetThumailService().deleteDatasetLogo(doomed);


        // ASSIGNMENTS
        for (RoleAssignment ra : ctxt.roles().directRoleAssignments(doomed)) {
            ctxt.em().remove(ra);
        }
        // ROLES
        for (DataverseRole ra : ctxt.roles().findByOwnerId(doomed.getId())) {
            ctxt.em().remove(ra);
        }

        GlobalIdServiceBean idServiceBean = GlobalIdServiceBean.getBean(ctxt);
        try {
            if (idServiceBean.alreadyExists(doomed)) {
                idServiceBean.deleteIdentifier(doomed);
                for (DataFile df : doomed.getFiles()) {
                    idServiceBean.deleteIdentifier(df);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Identifier deletion was not successfull:", e.getMessage());
        }
        Dataverse toReIndex = managedDoomed.getOwner();

        // DownloadDatasetLog entries
        ctxt.downloadDatasetDao().deleteByDatasetId(managedDoomed.getId());

        // dataset
        ctxt.em().remove(managedDoomed);

        // add potential Solr IDs of datasets to list for deletion
        String solrIdOfPublishedDatasetVersion = IndexServiceBean.solrDocIdentifierDataset + doomed.getId();
        datasetAndFileSolrIdsToDelete.add(solrIdOfPublishedDatasetVersion);
        String solrIdOfDraftDatasetVersion = IndexServiceBean.solrDocIdentifierDataset + doomed.getId() + IndexServiceBean.draftSuffix;
        datasetAndFileSolrIdsToDelete.add(solrIdOfDraftDatasetVersion);
        String solrIdOfDeaccessionedDatasetVersion = IndexServiceBean.solrDocIdentifierDataset + doomed.getId() + IndexServiceBean.deaccessionedSuffix;
        datasetAndFileSolrIdsToDelete.add(solrIdOfDeaccessionedDatasetVersion);
        IndexResponse resultOfSolrDeletionAttempt = ctxt.solrIndex().deleteMultipleSolrIds(datasetAndFileSolrIdsToDelete);
        logger.log(Level.FINE, "Result of attempt to delete dataset and file IDs from the search index: {0}", resultOfSolrDeletionAttempt.getMessage());

        ctxt.index().indexDataverse(toReIndex);
    }

}