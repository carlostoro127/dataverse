package edu.harvard.iq.dataverse.api;

import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import com.nimbusds.openid.connect.sdk.UserInfoResponse;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.DataFileServiceBean;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetFieldServiceBean;
import edu.harvard.iq.dataverse.DatasetFieldType;
import edu.harvard.iq.dataverse.DatasetLinkingDataverse;
import edu.harvard.iq.dataverse.DatasetLinkingServiceBean;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.DatasetVersionServiceBean;
import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.DataverseLinkingDataverse;
import edu.harvard.iq.dataverse.DataverseLinkingServiceBean;
import edu.harvard.iq.dataverse.DataverseRoleServiceBean;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import edu.harvard.iq.dataverse.DvObject;
import edu.harvard.iq.dataverse.DvObjectServiceBean;
import edu.harvard.iq.dataverse.EjbDataverseEngine;
import edu.harvard.iq.dataverse.GuestbookResponseServiceBean;
import edu.harvard.iq.dataverse.MetadataBlock;
import edu.harvard.iq.dataverse.MetadataBlockServiceBean;
import edu.harvard.iq.dataverse.PermissionServiceBean;
import edu.harvard.iq.dataverse.RoleAssigneeServiceBean;
import edu.harvard.iq.dataverse.UserNotificationServiceBean;
import edu.harvard.iq.dataverse.UserServiceBean;
import edu.harvard.iq.dataverse.actionlogging.ActionLogServiceBean;
import edu.harvard.iq.dataverse.authorization.AuthenticationProvider;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.DataverseRole;
import edu.harvard.iq.dataverse.authorization.RoleAssignee;
import edu.harvard.iq.dataverse.authorization.groups.GroupServiceBean;
import edu.harvard.iq.dataverse.authorization.providers.oauth2.oidc.OIDCAuthProvider;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.authorization.users.GuestUser;
import edu.harvard.iq.dataverse.authorization.users.PrivateUrlUser;
import edu.harvard.iq.dataverse.authorization.users.User;
import edu.harvard.iq.dataverse.confirmemail.ConfirmEmailServiceBean;
import edu.harvard.iq.dataverse.datacapturemodule.DataCaptureModuleServiceBean;
import edu.harvard.iq.dataverse.engine.command.Command;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.exception.IllegalCommandException;
import edu.harvard.iq.dataverse.engine.command.exception.PermissionException;
import edu.harvard.iq.dataverse.externaltools.ExternalToolServiceBean;
import edu.harvard.iq.dataverse.license.LicenseServiceBean;
import edu.harvard.iq.dataverse.metrics.MetricsServiceBean;
import edu.harvard.iq.dataverse.privateurl.PrivateUrlServiceBean;
import edu.harvard.iq.dataverse.locality.StorageSiteServiceBean;
import edu.harvard.iq.dataverse.search.savedsearch.SavedSearchServiceBean;
import edu.harvard.iq.dataverse.settings.FeatureFlags;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.BundleUtil;
import edu.harvard.iq.dataverse.util.SystemConfig;
import edu.harvard.iq.dataverse.util.UrlSignerUtil;
import edu.harvard.iq.dataverse.util.json.JsonParser;
import edu.harvard.iq.dataverse.util.json.NullSafeJsonBuilder;
import edu.harvard.iq.dataverse.validation.PasswordValidatorServiceBean;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import static org.apache.commons.lang3.StringUtils.isNumeric;

/**
 * Base class for API beans
 * @author michael
 */
public abstract class AbstractApiBean {

    private static final Logger logger = Logger.getLogger(AbstractApiBean.class.getName());
    private static final String DATAVERSE_KEY_HEADER_NAME = "X-Dataverse-key";
    private static final String OIDC_AUTH_SCHEME = "Bearer";
    private static final String PERSISTENT_ID_KEY=":persistentId";
    private static final String ALIAS_KEY=":alias";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_WF_IN_PROGRESS = "WORKFLOW_IN_PROGRESS";
    public static final String DATAVERSE_WORKFLOW_INVOCATION_HEADER_NAME = "X-Dataverse-invocationID";

    /**
     * Utility class to convey a proper error response using Java's exceptions.
     */
    public static class WrappedResponse extends Exception {
        private final Response response;

        public WrappedResponse(Response response) {
            this.response = response;
        }

        public WrappedResponse( Throwable cause, Response response ) {
            super( cause );
            this.response = response;
        }

        public Response getResponse() {
            return response;
        }

        /**
         * Creates a new response, based on the original response and the passed message.
         * Typical use would be to add a better error message to the HTTP response.
         * @param message additional message to be added to the response.
         * @return A Response with updated message field.
         */
        public Response refineResponse( String message ) {
            final Status statusCode = Response.Status.fromStatusCode(response.getStatus());
            String baseMessage = getWrappedMessageWhenJson();

            if ( baseMessage == null ) {
                final Throwable cause = getCause();
                baseMessage = (cause!=null ? cause.getMessage() : "");
            }
            return error(statusCode, message+" "+baseMessage);
        }

        /**
         * In the common case of the wrapped response being of type JSON,
         * return the message field it has (if any).
         * @return the content of a message field, or {@code null}.
         */
        String getWrappedMessageWhenJson() {
            if ( response.getMediaType().equals(MediaType.APPLICATION_JSON_TYPE) ) {
                Object entity = response.getEntity();
                if ( entity == null ) return null;

                String json = entity.toString();
                try ( StringReader rdr = new StringReader(json) ){
                    JsonReader jrdr = Json.createReader(rdr);
                    JsonObject obj = jrdr.readObject();
                    if ( obj.containsKey("message") ) {
                        JsonValue message = obj.get("message");
                        return message.getValueType() == ValueType.STRING ? obj.getString("message") : message.toString();
                    } else {
                        return null;
                    }
                }
            } else {
                return null;
            }
        }
    }

    @EJB
    protected EjbDataverseEngine engineSvc;

    @EJB
    protected DatasetServiceBean datasetSvc;
    
    @EJB
    protected DataFileServiceBean fileService;

    @EJB
    protected DataverseServiceBean dataverseSvc;

    @EJB
    protected AuthenticationServiceBean authSvc;

    @EJB
    protected DatasetFieldServiceBean datasetFieldSvc;

    @EJB
    protected MetadataBlockServiceBean metadataBlockSvc;

    @EJB
    protected LicenseServiceBean licenseSvc;

    @EJB
    protected UserServiceBean userSvc;

	@EJB
	protected DataverseRoleServiceBean rolesSvc;

    @EJB
    protected SettingsServiceBean settingsSvc;

    @EJB
    protected RoleAssigneeServiceBean roleAssigneeSvc;

    @EJB
    protected PermissionServiceBean permissionSvc;

    @EJB
    protected GroupServiceBean groupSvc;

    @EJB
    protected ActionLogServiceBean actionLogSvc;

    @EJB
    protected SavedSearchServiceBean savedSearchSvc;

    @EJB
    protected PrivateUrlServiceBean privateUrlSvc;

    @EJB
    protected ConfirmEmailServiceBean confirmEmailSvc;

    @EJB
    protected UserNotificationServiceBean userNotificationSvc;

    @EJB
    protected DatasetVersionServiceBean datasetVersionSvc;

    @EJB
    protected SystemConfig systemConfig;

    @EJB
    protected DataCaptureModuleServiceBean dataCaptureModuleSvc;
    
    @EJB
    protected DatasetLinkingServiceBean dsLinkingService;
    
    @EJB
    protected DataverseLinkingServiceBean dvLinkingService;

    @EJB
    protected PasswordValidatorServiceBean passwordValidatorService;

    @EJB
    protected ExternalToolServiceBean externalToolService;

    @EJB
    DataFileServiceBean fileSvc;

    @EJB
    StorageSiteServiceBean storageSiteSvc;

    @EJB
    MetricsServiceBean metricsSvc;
    
    @EJB 
    DvObjectServiceBean dvObjSvc;
    
    @EJB 
    GuestbookResponseServiceBean gbRespSvc;

    @PersistenceContext(unitName = "VDCNet-ejbPU")
    protected EntityManager em;

    @Context
    protected HttpServletRequest httpRequest;

    /**
     * For pretty printing (indenting) of JSON output.
     */
    public enum Format {

        PRETTY
    }

    private final LazyRef<JsonParser> jsonParserRef = new LazyRef<>(new Callable<JsonParser>() {
        @Override
        public JsonParser call() throws Exception {
            return new JsonParser(datasetFieldSvc, metadataBlockSvc,settingsSvc, licenseSvc);
        }
    });

    /**
     * Functional interface for handling HTTP requests in the APIs.
     *
     * @see #response(edu.harvard.iq.dataverse.api.AbstractApiBean.DataverseRequestHandler)
     */
    protected static interface DataverseRequestHandler {
        Response handle( DataverseRequest u ) throws WrappedResponse;
    }


    /* ===================== *\
     *  Utility Methods      *
     *  Get that DSL feelin' *
    \* ===================== */

    protected JsonParser jsonParser() {
        return jsonParserRef.get();
    }

    protected boolean parseBooleanOrDie( String input ) throws WrappedResponse {
        if (input == null ) throw new WrappedResponse( badRequest("Boolean value missing"));
        input = input.trim();
        if ( Util.isBoolean(input) ) {
            return Util.isTrue(input);
        } else {
            throw new WrappedResponse( badRequest("Illegal boolean value '" + input + "'"));
        }
    }

     /**
     * Returns the {@code key} query parameter from the current request, or {@code null} if
     * the request has no such parameter.
     * @param key Name of the requested parameter.
     * @return Value of the requested parameter in the current request.
     */
    protected String getRequestParameter( String key ) {
        return httpRequest.getParameter(key);
    }

    protected String getRequestApiKey() {
        String headerParamApiKey = httpRequest.getHeader(DATAVERSE_KEY_HEADER_NAME);
        String queryParamApiKey = httpRequest.getParameter("key");
                
        return headerParamApiKey!=null ? headerParamApiKey : queryParamApiKey;
    }
    
    protected String getRequestWorkflowInvocationID() {
        String headerParamWFKey = httpRequest.getHeader(DATAVERSE_WORKFLOW_INVOCATION_HEADER_NAME);
        String queryParamWFKey = httpRequest.getParameter("invocationID");
                
        return headerParamWFKey!=null ? headerParamWFKey : queryParamWFKey;
    }

    /* ========= *\
     *  Finders  *
    \* ========= */
    protected RoleAssignee findAssignee(String identifier) {
        try {
            RoleAssignee roleAssignee = roleAssigneeSvc.getRoleAssignee(identifier);
            return roleAssignee;
        } catch (EJBException ex) {
            Throwable cause = ex;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            logger.log(Level.INFO, "Exception caught looking up RoleAssignee based on identifier ''{0}'': {1}", new Object[]{identifier, cause.getMessage()});
            return null;
        }
    }

    /**
     *
     * @param apiKey the key to find the user with
     * @return the user, or null
     * @see #findUserOrDie(java.lang.String)
     */
    protected AuthenticatedUser findUserByApiToken( String apiKey ) {
        return authSvc.lookupUser(apiKey);
    }

    /**
     * Returns the user of pointed by the API key, or the guest user
     * @return a user, may be a guest user.
     * @throws edu.harvard.iq.dataverse.api.AbstractApiBean.WrappedResponse iff there is an api key present, but it is invalid.
     */
    protected User findUserOrDie() throws WrappedResponse {
        final String requestApiKey = getRequestApiKey();
        final String requestWFKey = getRequestWorkflowInvocationID();
        if (requestApiKey == null && requestWFKey == null && getRequestParameter(UrlSignerUtil.SIGNED_URL_TOKEN)==null && !(FeatureFlags.API_OIDC_ACCESS.enabled() && getOidcBearerToken(httpRequest).isPresent())) {
            return GuestUser.get();
        }
        PrivateUrlUser privateUrlUser = privateUrlSvc.getPrivateUrlUserFromToken(requestApiKey);
        // For privateUrlUsers restricted to anonymized access, all api calls are off-limits except for those used in the UI
        // to download the file or image thumbs
        if (privateUrlUser != null) {
            if (privateUrlUser.hasAnonymizedAccess()) {
                String pathInfo = httpRequest.getPathInfo();
                String prefix= "/access/datafile/";
                if (!(pathInfo.startsWith(prefix) && !pathInfo.substring(prefix.length()).contains("/"))) {
                    logger.info("Anonymized access request for " + pathInfo);
                    throw new WrappedResponse(error(Status.UNAUTHORIZED, "API Access not allowed with this Key"));
                }
            }
            return privateUrlUser;
        }
        return findAuthenticatedUserOrDie(requestApiKey, requestWFKey);
    }

    /**
     * Finds the authenticated user, based on (in order):
     * <ol>
     *  <li>The key in the HTTP header {@link #DATAVERSE_KEY_HEADER_NAME}</li>
     *  <li>The key in the query parameter {@code key}
     * </ol>
     *
     * If no user is found, throws a wrapped bad api key (HTTP UNAUTHORIZED) response.
     *
     * @return The authenticated user which owns the passed api key
     * @throws edu.harvard.iq.dataverse.api.AbstractApiBean.WrappedResponse in case said user is not found.
     */
    protected AuthenticatedUser findAuthenticatedUserOrDie() throws WrappedResponse {
        return findAuthenticatedUserOrDie(getRequestApiKey(), getRequestWorkflowInvocationID());
    }


    private AuthenticatedUser findAuthenticatedUserOrDie( String key, String wfid ) throws WrappedResponse {
        if (key != null) {
            // No check for deactivated user because it's done in authSvc.lookupUser.
            AuthenticatedUser authUser = authSvc.lookupUser(key);

            if (authUser != null) {
                authUser = userSvc.updateLastApiUseTime(authUser);

                return authUser;
            }
            else {
                throw new WrappedResponse(badApiKey(key));
            }
        } else if (wfid != null) {
            AuthenticatedUser authUser = authSvc.lookupUserForWorkflowInvocationID(wfid);
            if (authUser != null) {
                return authUser;
            } else {
                throw new WrappedResponse(badWFKey(wfid));
            }
        } else if (getRequestParameter(UrlSignerUtil.SIGNED_URL_TOKEN) != null) {
            AuthenticatedUser authUser = getAuthenticatedUserFromSignedUrl();
            if (authUser != null) {
                return authUser;
            }
        
        } else if (FeatureFlags.API_OIDC_ACCESS.enabled() && getOidcBearerToken(httpRequest).isPresent()) {
            UserInfo userInfo = verifyOidcBearerToken(getOidcBearerToken(httpRequest).get());
            
            // TODO: Only usable for OIDC users for now, just look it up via the subject.
            //       This will need to be modified to provide mappings somehow for existing non-OIDC-users.
            // TODO: If we keep the current login infrastructure alive, we should introduce a common static
            //       method in OIDCAuthProvider to create the identifier in both places.
            AuthenticatedUser authUser = authSvc.getAuthenticatedUser(userInfo.getSubject().getValue());
            
            // TODO: this is code dup par excellence. Needs refactoring. Maybe fine for Proof-of-Concept.
            if (authUser != null) {
                authUser = userSvc.updateLastApiUseTime(authUser);
                return authUser;
            }
        }
        //Just send info about the apiKey - workflow users will learn about invocationId elsewhere
        throw new WrappedResponse(badApiKey(null));
    }
    
    private AuthenticatedUser getAuthenticatedUserFromSignedUrl() {
        AuthenticatedUser authUser = null;
        // The signedUrl contains a param telling which user this is supposed to be for.
        // We don't trust this. So we lookup that user, and get their API key, and use
        // that as a secret in validating the signedURL. If the signature can't be
        // validated with their key, the user (or their API key) has been changed and
        // we reject the request.
        // ToDo - add null checks/ verify that calling methods catch things.
        String user = httpRequest.getParameter("user");
        AuthenticatedUser targetUser = authSvc.getAuthenticatedUser(user);
        String key = JvmSettings.API_SIGNING_SECRET.lookupOptional().orElse("")
                + authSvc.findApiTokenByUser(targetUser).getTokenString();
        String signedUrl = httpRequest.getRequestURL().toString() + "?" + httpRequest.getQueryString();
        String method = httpRequest.getMethod();
        boolean validated = UrlSignerUtil.isValidUrl(signedUrl, user, method, key);
        if (validated) {
            authUser = targetUser;
        }
        return authUser;
    }
    
    /**
     * Retrieve the raw, encoded token value from the Authorization Bearer HTTP header as defined in RFC 6750
     * @param request The HTTP request coming in
     * @return An {@link Optional} either empty if not present or the raw token from the header
     */
    Optional<String> getOidcBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        
        if (authHeader != null && authHeader.toLowerCase().startsWith(OIDC_AUTH_SCHEME.toLowerCase() + " ")) {
            return Optional.of(authHeader);
        } else {
            return Optional.empty();
        }
    }
    
    
    /**
     * <p>Verify an OIDC access token by dealing the access token for a UserInfo object from the provider</p>
     * <p>
     * TODO: This is a proof of concept, providing value for IQSS#9229 and first steps for our SPA move. It ...
     *       - will need more tweaks (see inline comments),
     *       - should be extended to support JWT access tokens to avoid the extra detour to the OIDC provider,
     *       - needs to be moved to a distinct place when we head for authentication filters in future iterations.
     * </p>
     *
     * @param token The string containing the encoded JWT
     * @return
     */
    UserInfo verifyOidcBearerToken(String token) throws WrappedResponse {
        try {
            BearerAccessToken accessToken = BearerAccessToken.parse(token);
            
            // Get list of all authentication providers using Open ID Connect
            List<OIDCAuthProvider> providers = authSvc.getAuthenticationProviderIdsOfType(OIDCAuthProvider.class).stream()
                .map(providerId -> (OIDCAuthProvider) authSvc.getAuthenticationProvider(providerId))
                .collect(Collectors.toUnmodifiableList());
            
            // Iterate over all OIDC providers if multiple.
            for (OIDCAuthProvider provider : providers) {
    
                // Retrieve data of the user accessing the API from the provider.
                // No need to introspect the token here, the userInfoRequest also validates the token, as the provider
                // is the source of truth.
                try {
                    HTTPResponse response = new UserInfoRequest(provider.getUserInfoEndpointURI(), accessToken)
                        .toHTTPRequest()
                        .send();
    
                    UserInfoResponse infoResponse = UserInfoResponse.parse(response);
    
                    // If error, throw 401 error exception
                    if (! infoResponse.indicatesSuccess() ) {
                        ErrorObject error = infoResponse.toErrorResponse().getErrorObject();
                        logger.log(Level.FINE,
                            "UserInfo could not be retrieved by access token from provider {0}: {1}",
                            new String[]{provider.getId(), error.getDescription()});
                    // Success, simply return the user info
                    } else {
                        return infoResponse.toSuccessResponse().getUserInfo();
                    }
                } catch (ParseException | IOException e) {
                    logger.log(Level.WARNING,
                        "Could not retrieve user info for provider " + provider.getId() + ", skipping", e);
                }
            }
        } catch (ParseException e) {
            logger.log(Level.FINE, "Could not parse bearer access token", e);
            throw new WrappedResponse(error(Status.UNAUTHORIZED, "Could not parse bearer access token"));
        }
        
        // No UserInfo returned means we have an invalid access token. (It could also mean we have no OIDC
        // provider, but this would also mean this is an invalid request, as there will be no user available...)
        // TODO: Should this include more details about the request?
        logger.log(Level.FINE, "Unauthorized bearer access token detected");
        throw new WrappedResponse(error(Status.UNAUTHORIZED, "Unauthorized bearer access token"));
    }
    
    protected Dataverse findDataverseOrDie( String dvIdtf ) throws WrappedResponse {
        Dataverse dv = findDataverse(dvIdtf);
        if ( dv == null ) {
            throw new WrappedResponse(error( Response.Status.NOT_FOUND, "Can't find dataverse with identifier='" + dvIdtf + "'"));
        }
        return dv;
    }
    
    protected DataverseLinkingDataverse findDataverseLinkingDataverseOrDie(String dataverseId, String linkedDataverseId) throws WrappedResponse {
        DataverseLinkingDataverse dvld;
        Dataverse dataverse = findDataverseOrDie(dataverseId);
        Dataverse linkedDataverse = findDataverseOrDie(linkedDataverseId);
        try {
            dvld = dvLinkingService.findDataverseLinkingDataverse(dataverse.getId(), linkedDataverse.getId());
            if (dvld == null) {
                throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.dataverselinking.error.not.found.ids", Arrays.asList(dataverseId, linkedDataverseId))));
            }
            return dvld;
        } catch (NumberFormatException nfe) {
            throw new WrappedResponse(
                    badRequest(BundleUtil.getStringFromBundle("find.dataverselinking.error.not.found.bad.ids", Arrays.asList(dataverseId, linkedDataverseId))));
        }
    }

    protected Dataset findDatasetOrDie(String id) throws WrappedResponse {
        Dataset dataset;
        if (id.equals(PERSISTENT_ID_KEY)) {
            String persistentId = getRequestParameter(PERSISTENT_ID_KEY.substring(1));
            if (persistentId == null) {
                throw new WrappedResponse(
                        badRequest(BundleUtil.getStringFromBundle("find.dataset.error.dataset_id_is_null", Collections.singletonList(PERSISTENT_ID_KEY.substring(1)))));
            }
            dataset = datasetSvc.findByGlobalId(persistentId);
            if (dataset == null) {
                throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.dataset.error.dataset.not.found.persistentId", Collections.singletonList(persistentId))));
            }
            return dataset;

        } else {
            try {
                dataset = datasetSvc.find(Long.parseLong(id));
                if (dataset == null) {
                    throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.dataset.error.dataset.not.found.id", Collections.singletonList(id))));
                }
                return dataset;
            } catch (NumberFormatException nfe) {
                throw new WrappedResponse(
                        badRequest(BundleUtil.getStringFromBundle("find.dataset.error.dataset.not.found.bad.id", Collections.singletonList(id))));
            }
        }
    }
    
    protected DataFile findDataFileOrDie(String id) throws WrappedResponse {
        DataFile datafile;
        if (id.equals(PERSISTENT_ID_KEY)) {
            String persistentId = getRequestParameter(PERSISTENT_ID_KEY.substring(1));
            if (persistentId == null) {
                throw new WrappedResponse(
                        badRequest(BundleUtil.getStringFromBundle("find.dataset.error.dataset_id_is_null", Collections.singletonList(PERSISTENT_ID_KEY.substring(1)))));
            }
            datafile = fileService.findByGlobalId(persistentId);
            if (datafile == null) {
                throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.datafile.error.dataset.not.found.persistentId", Collections.singletonList(persistentId))));
            }
            return datafile;
        } else {
            try {
                datafile = fileService.find(Long.parseLong(id));
                if (datafile == null) {
                    throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.datafile.error.datafile.not.found.id", Collections.singletonList(id))));
                }
                return datafile;
            } catch (NumberFormatException nfe) {
                throw new WrappedResponse(
                        badRequest(BundleUtil.getStringFromBundle("find.datafile.error.datafile.not.found.bad.id", Collections.singletonList(id))));
            }
        }
    }
       
    protected DataverseRole findRoleOrDie(String id) throws WrappedResponse {
        DataverseRole role;
        if (id.equals(ALIAS_KEY)) {
            String alias = getRequestParameter(ALIAS_KEY.substring(1));
            try {
                return em.createNamedQuery("DataverseRole.findDataverseRoleByAlias", DataverseRole.class)
                        .setParameter("alias", alias)
                        .getSingleResult();

            //Should not be a multiple result exception due to table constraint
            } catch (NoResultException nre) {
                throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.dataverse.role.error.role.not.found.alias", Collections.singletonList(alias))));
            }

        } else {

            try {
                role = rolesSvc.find(Long.parseLong(id));
                if (role == null) {
                    throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.dataverse.role.error.role.not.found.id", Collections.singletonList(id))));
                } else {
                    return role;
                }

            } catch (NumberFormatException nfe) {
                throw new WrappedResponse(
                        badRequest(BundleUtil.getStringFromBundle("find.dataverse.role.error.role.not.found.bad.id", Collections.singletonList(id))));
            }
        }
    }
    
    protected DatasetLinkingDataverse findDatasetLinkingDataverseOrDie(String datasetId, String linkingDataverseId) throws WrappedResponse {
        DatasetLinkingDataverse dsld;
        Dataverse linkingDataverse = findDataverseOrDie(linkingDataverseId);

        if (datasetId.equals(PERSISTENT_ID_KEY)) {
            String persistentId = getRequestParameter(PERSISTENT_ID_KEY.substring(1));
            if (persistentId == null) {
                throw new WrappedResponse(
                        badRequest(BundleUtil.getStringFromBundle("find.dataset.error.dataset_id_is_null", Collections.singletonList(PERSISTENT_ID_KEY.substring(1)))));
            }
            
            Dataset dataset = datasetSvc.findByGlobalId(persistentId);
            if (dataset == null) {
                throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.dataset.error.dataset.not.found.persistentId", Collections.singletonList(persistentId))));
            }
            datasetId = dataset.getId().toString();
        } 
        try {
            dsld = dsLinkingService.findDatasetLinkingDataverse(Long.parseLong(datasetId), linkingDataverse.getId());
            if (dsld == null) {
                throw new WrappedResponse(notFound(BundleUtil.getStringFromBundle("find.datasetlinking.error.not.found.ids", Arrays.asList(datasetId, linkingDataverse.getId().toString()))));
            }
            return dsld;
        } catch (NumberFormatException nfe) {
            throw new WrappedResponse(
                    badRequest(BundleUtil.getStringFromBundle("find.datasetlinking.error.not.found.bad.ids", Arrays.asList(datasetId, linkingDataverse.getId().toString()))));
        }
    }

    protected DataverseRequest createDataverseRequest( User u )  {
        return new DataverseRequest(u, httpRequest);
    }

	protected Dataverse findDataverse( String idtf ) {
		return isNumeric(idtf) ? dataverseSvc.find(Long.parseLong(idtf))
	 							  : dataverseSvc.findByAlias(idtf);
	}

	protected DvObject findDvo( Long id ) {
		return em.createNamedQuery("DvObject.findById", DvObject.class)
				.setParameter("id", id)
				.getSingleResult();
	}

    /**
     * Tries to find a DvObject. If the passed id can be interpreted as a number,
     * it tries to get the DvObject by its id. Else, it tries to get a {@link Dataverse}
     * with that alias. If that fails, tries to get a {@link Dataset} with that global id.
     * @param id a value identifying the DvObject, either numeric of textual.
     * @return A DvObject, or {@code null}
     */
	protected DvObject findDvo( String id ) {
        if ( isNumeric(id) ) {
            return findDvo( Long.valueOf(id)) ;
        } else {
            Dataverse d = dataverseSvc.findByAlias(id);
            return ( d != null ) ?
                    d : datasetSvc.findByGlobalId(id);

        }
	}

    protected <T> T failIfNull( T t, String errorMessage ) throws WrappedResponse {
        if ( t != null ) return t;
        throw new WrappedResponse( error( Response.Status.BAD_REQUEST,errorMessage) );
    }

    protected MetadataBlock findMetadataBlock(Long id)  {
        return metadataBlockSvc.findById(id);
    }
    protected MetadataBlock findMetadataBlock(String idtf) throws NumberFormatException {
        return metadataBlockSvc.findByName(idtf);
    }

    protected DatasetFieldType findDatasetFieldType(String idtf) throws NumberFormatException {
        return isNumeric(idtf) ? datasetFieldSvc.find(Long.parseLong(idtf))
                : datasetFieldSvc.findByNameOpt(idtf);
    }

    /* =================== *\
     *  Command Execution  *
    \* =================== */

    /**
     * Executes a command, and returns the appropriate result/HTTP response.
     * @param <T> Return type for the command
     * @param cmd The command to execute.
     * @return Value from the command
     * @throws edu.harvard.iq.dataverse.api.AbstractApiBean.WrappedResponse Unwrap and return.
     * @see #response(java.util.concurrent.Callable)
     */
    protected <T> T execCommand( Command<T> cmd ) throws WrappedResponse {
        try {
            return engineSvc.submit(cmd);

        } catch (IllegalCommandException ex) {
            //for 8859 for api calls that try to update datasets with TOA out of compliance
                if (ex.getMessage().toLowerCase().contains("terms of use")){
                    throw new WrappedResponse(ex, conflict(ex.getMessage()));
                }
            throw new WrappedResponse( ex, forbidden(ex.getMessage() ) );
        } catch (PermissionException ex) {
            /**
             * TODO Is there any harm in exposing ex.getLocalizedMessage()?
             * There's valuable information in there that can help people reason
             * about permissions! The formatting of the error would need to be
             * cleaned up but here's an example the helpful information:
             *
             * "User :guest is not permitted to perform requested action.Can't
             * execute command
             * edu.harvard.iq.dataverse.engine.command.impl.MoveDatasetCommand@50b150d9,
             * because request [DataverseRequest user:[GuestUser
             * :guest]@127.0.0.1] is missing permissions [AddDataset,
             * PublishDataset] on Object mra"
             *
             * Right now, the error that's visible via API (and via GUI
             * sometimes?) doesn't have much information in it:
             *
             * "User @jsmith is not permitted to perform requested action."
             */
            throw new WrappedResponse(error(Response.Status.UNAUTHORIZED,
                                                    "User " + cmd.getRequest().getUser().getIdentifier() + " is not permitted to perform requested action.") );

        } catch (CommandException ex) {
            Logger.getLogger(AbstractApiBean.class.getName()).log(Level.SEVERE, "Error while executing command " + cmd, ex);
            throw new WrappedResponse(ex, error(Status.INTERNAL_SERVER_ERROR, ex.getMessage()));
        }
    }

    /**
     * A syntactically nicer way of using {@link #execCommand(edu.harvard.iq.dataverse.engine.command.Command)}.
     * @param hdl The block to run.
     * @return HTTP Response appropriate for the way {@code hdl} executed.
     */
    protected Response response( Callable<Response> hdl ) {
        try {
            return hdl.call();
        } catch ( WrappedResponse rr ) {
            return rr.getResponse();
        } catch ( Exception ex ) {
            String incidentId = UUID.randomUUID().toString();
            logger.log(Level.SEVERE, "API internal error " + incidentId +": " + ex.getMessage(), ex);
            return Response.status(500)
                .entity( Json.createObjectBuilder()
                             .add("status", "ERROR")
                             .add("code", 500)
                             .add("message", "Internal server error. More details available at the server logs.")
                             .add("incidentId", incidentId)
                        .build())
                .type("application/json").build();
        }
    }

    /**
     * The preferred way of handling a request that requires a user. The system
     * looks for the user and, if found, handles it to the handler for doing the
     * actual work.
     *
     * This is a relatively secure way to handle things, since if the user is not
     * found, the response is about the bad API key, rather than something else
     * (say, 404 NOT FOUND which leaks information about the existence of the
     * sought object).
     *
     * @param hdl handling code block.
     * @return HTTP Response appropriate for the way {@code hdl} executed.
     */
    protected Response response( DataverseRequestHandler hdl ) {
        try {
            return hdl.handle(createDataverseRequest(findUserOrDie()));
        } catch ( WrappedResponse rr ) {
            return rr.getResponse();
        } catch ( Exception ex ) {
            String incidentId = UUID.randomUUID().toString();
            logger.log(Level.SEVERE, "API internal error " + incidentId +": " + ex.getMessage(), ex);
            return Response.status(500)
                .entity( Json.createObjectBuilder()
                             .add("status", "ERROR")
                             .add("code", 500)
                             .add("message", "Internal server error. More details available at the server logs.")
                             .add("incidentId", incidentId)
                        .build())
                .type("application/json").build();
        }
    }

    /* ====================== *\
     *  HTTP Response methods *
    \* ====================== */

    protected Response ok( JsonArrayBuilder bld ) {
        return Response.ok(Json.createObjectBuilder()
            .add("status", STATUS_OK)
            .add("data", bld).build())
            .type(MediaType.APPLICATION_JSON).build();
    }
    
    protected Response ok( JsonArray ja ) {
        return Response.ok(Json.createObjectBuilder()
            .add("status", STATUS_OK)
            .add("data", ja).build())
            .type(MediaType.APPLICATION_JSON).build();
    }

    protected Response ok( JsonObjectBuilder bld ) {
        return Response.ok( Json.createObjectBuilder()
            .add("status", STATUS_OK)
            .add("data", bld).build() )
            .type(MediaType.APPLICATION_JSON)
            .build();
    }
    
    protected Response ok( JsonObject jo ) {
        return Response.ok( Json.createObjectBuilder()
                .add("status", STATUS_OK)
                .add("data", jo).build() )
                .type(MediaType.APPLICATION_JSON)
                .build();    
    }

    protected Response ok( String msg ) {
        return Response.ok().entity(Json.createObjectBuilder()
            .add("status", STATUS_OK)
            .add("data", Json.createObjectBuilder().add("message",msg)).build() )
            .type(MediaType.APPLICATION_JSON)
            .build();
    }
    
    protected Response ok( String msg, JsonObjectBuilder bld  ) {
        return Response.ok().entity(Json.createObjectBuilder()
            .add("status", STATUS_OK)
            .add("message", Json.createObjectBuilder().add("message",msg))     
            .add("data", bld).build())      
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    protected Response ok( boolean value ) {
        return Response.ok().entity(Json.createObjectBuilder()
            .add("status", STATUS_OK)
            .add("data", value).build() ).build();
    }

    /**
     * @param data Payload to return.
     * @param mediaType Non-JSON media type.
     * @param downloadFilename - add Content-Disposition header to suggest filename if not null
     * @return Non-JSON response, such as a shell script.
     */
    protected Response ok(String data, MediaType mediaType, String downloadFilename) {
        ResponseBuilder res =Response.ok().entity(data).type(mediaType);
        if(downloadFilename != null) {
            res = res.header("Content-Disposition", "attachment; filename=" + downloadFilename);
        }
        return res.build();
    }

    protected Response created( String uri, JsonObjectBuilder bld ) {
        return Response.created( URI.create(uri) )
                .entity( Json.createObjectBuilder()
                .add("status", "OK")
                .add("data", bld).build())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
    
    protected Response accepted(JsonObjectBuilder bld) {
        return Response.accepted()
                .entity(Json.createObjectBuilder()
                        .add("status", STATUS_WF_IN_PROGRESS)
                        .add("data",bld).build()
                ).build();
    }
    
    protected Response accepted() {
        return Response.accepted()
                .entity(Json.createObjectBuilder()
                        .add("status", STATUS_WF_IN_PROGRESS).build()
                ).build();
    }

    protected Response notFound( String msg ) {
        return error(Status.NOT_FOUND, msg);
    }

    protected Response badRequest( String msg ) {
        return error( Status.BAD_REQUEST, msg );
    }
    
    protected Response forbidden( String msg ) {
        return error( Status.FORBIDDEN, msg );
    }
    
    protected Response conflict( String msg ) {
        return error( Status.CONFLICT, msg );
    }
    
    protected Response badApiKey( String apiKey ) {
        return error(Status.UNAUTHORIZED, (apiKey != null ) ? "Bad api key " : "Please provide a key query parameter (?key=XXX) or via the HTTP header " + DATAVERSE_KEY_HEADER_NAME);
    }

    protected Response badWFKey( String wfId ) {
        String message = (wfId != null ) ? "Bad workflow invocationId " : "Please provide an invocationId query parameter (?invocationId=XXX) or via the HTTP header " + DATAVERSE_WORKFLOW_INVOCATION_HEADER_NAME;
        return error(Status.UNAUTHORIZED, message );
    }
    
    protected Response permissionError( PermissionException pe ) {
        return permissionError( pe.getMessage() );
    }

    protected Response permissionError( String message ) {
        return unauthorized( message );
    }
    
    protected Response unauthorized( String message ) {
        return error( Status.UNAUTHORIZED, message );
    }

    protected static Response error( Status sts, String msg ) {
        return Response.status(sts)
                .entity( NullSafeJsonBuilder.jsonObjectBuilder()
                        .add("status", STATUS_ERROR)
                        .add( "message", msg ).build()
                ).type(MediaType.APPLICATION_JSON_TYPE).build();
    }
}

class LazyRef<T> {
    private interface Ref<T> {
        T get();
    }

    private Ref<T> ref;

    public LazyRef( final Callable<T> initer ) {
        ref = () -> {
            try {
                final T t = initer.call();
                ref = () -> t;
                return ref.get();
            } catch (Exception ex) {
                Logger.getLogger(LazyRef.class.getName()).log(Level.SEVERE, null, ex);
                return null;
            }
        };
    }

    public T get()  {
        return ref.get();
    }
}
