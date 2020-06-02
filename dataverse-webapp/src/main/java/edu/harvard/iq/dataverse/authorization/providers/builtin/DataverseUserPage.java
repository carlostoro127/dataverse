package edu.harvard.iq.dataverse.authorization.providers.builtin;

import edu.harvard.iq.dataverse.DataFileServiceBean;
import edu.harvard.iq.dataverse.DatasetDao;
import edu.harvard.iq.dataverse.DataverseDao;
import edu.harvard.iq.dataverse.DataverseSession;
import edu.harvard.iq.dataverse.PermissionServiceBean;
import edu.harvard.iq.dataverse.PermissionsWrapper;
import edu.harvard.iq.dataverse.UserServiceBean;
import edu.harvard.iq.dataverse.authorization.AuthUtil;
import edu.harvard.iq.dataverse.authorization.AuthenticationProvider;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.UserRecordIdentifier;
import edu.harvard.iq.dataverse.authorization.groups.GroupServiceBean;
import edu.harvard.iq.dataverse.common.BundleUtil;
import edu.harvard.iq.dataverse.consent.ConsentDto;
import edu.harvard.iq.dataverse.consent.ConsentService;
import edu.harvard.iq.dataverse.dataset.datasetversion.DatasetVersionServiceBean;
import edu.harvard.iq.dataverse.mail.confirmemail.ConfirmEmailException;
import edu.harvard.iq.dataverse.mail.confirmemail.ConfirmEmailServiceBean;
import edu.harvard.iq.dataverse.mail.confirmemail.ConfirmEmailUtil;
import edu.harvard.iq.dataverse.mydata.MyDataPage;
import edu.harvard.iq.dataverse.notification.NotificationObjectType;
import edu.harvard.iq.dataverse.notification.UserNotificationService;
import edu.harvard.iq.dataverse.persistence.DvObject;
import edu.harvard.iq.dataverse.persistence.config.EMailValidator;
import edu.harvard.iq.dataverse.persistence.datafile.DataFile;
import edu.harvard.iq.dataverse.persistence.dataset.Dataset;
import edu.harvard.iq.dataverse.persistence.dataverse.Dataverse;
import edu.harvard.iq.dataverse.persistence.group.Group;
import edu.harvard.iq.dataverse.persistence.user.AuthenticatedUser;
import edu.harvard.iq.dataverse.persistence.user.AuthenticatedUserDisplayInfo;
import edu.harvard.iq.dataverse.persistence.user.BuiltinUser;
import edu.harvard.iq.dataverse.persistence.user.ConfirmEmailData;
import edu.harvard.iq.dataverse.persistence.user.NotificationType;
import edu.harvard.iq.dataverse.persistence.user.RoleAssignment;
import edu.harvard.iq.dataverse.persistence.user.UserNameValidator;
import edu.harvard.iq.dataverse.persistence.user.UserNotification;
import edu.harvard.iq.dataverse.persistence.user.UserNotificationDao;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.settings.SettingsWrapper;
import edu.harvard.iq.dataverse.util.JsfHelper;
import edu.harvard.iq.dataverse.util.SystemConfig;
import edu.harvard.iq.dataverse.validation.PasswordValidatorServiceBean;
import io.vavr.control.Option;
import org.apache.commons.lang.StringUtils;
import org.hibernate.validator.constraints.NotBlank;
import org.primefaces.event.TabChangeEvent;

import javax.ejb.EJB;
import javax.faces.application.FacesMessage;
import javax.faces.component.UIComponent;
import javax.faces.component.UIInput;
import javax.faces.context.FacesContext;
import javax.faces.event.ActionEvent;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static edu.harvard.iq.dataverse.util.JsfHelper.JH;

/**
 *
 */
@ViewScoped
@Named("DataverseUserPage")
public class DataverseUserPage implements java.io.Serializable {

    private static final Logger logger = Logger.getLogger(DataverseUserPage.class.getCanonicalName());

    public enum EditMode {
        CREATE, EDIT, CHANGE_PASSWORD, FORGOT
    }

    @Inject
    DataverseSession session;
    @EJB
    DataverseDao dataverseDao;
    @EJB
    private UserNotificationService userNotificationService;
    @EJB
    private UserNotificationDao userNotificationDao;
    @EJB
    UserServiceBean userService;
    @EJB
    DatasetDao datasetDao;
    @EJB
    DataFileServiceBean fileService;
    @EJB
    DatasetVersionServiceBean datasetVersionService;
    @EJB
    PermissionServiceBean permissionService;
    @EJB
    BuiltinUserServiceBean builtinUserService;
    @EJB
    AuthenticationServiceBean authenticationService;
    @EJB
    ConfirmEmailServiceBean confirmEmailService;
    @EJB
    SystemConfig systemConfig;
    @EJB
    GroupServiceBean groupService;
    @EJB
    PasswordValidatorServiceBean passwordValidatorService;
    @Inject
    SettingsWrapper settingsWrapper;
    @Inject
    SettingsServiceBean settingsService;
    @Inject
    MyDataPage mydatapage;
    @Inject
    PermissionsWrapper permissionsWrapper;

    @Inject
    private ConsentService consentService;

    private AuthenticatedUser currentUser;
    private BuiltinUser builtinUser;
    private AuthenticatedUserDisplayInfo userDisplayInfo;
    private transient AuthenticationProvider userAuthProvider;
    private EditMode editMode;
    private String redirectPage = "dataverse.xhtml";

    @NotBlank(message = "{password.retype}")
    private String inputPassword;

    @NotBlank(message = "{password.current}")
    private String currentPassword;
    private Long dataverseId;
    private List<UserNotification> notificationsList;
    private int activeIndex;
    private String selectTab = "somedata";
    UIInput usernameField;

    private Locale preferredNotificationsLanguage = null;


    private String username;
    boolean nonLocalLoginEnabled;
    private List<String> passwordErrors;
    private List<ConsentDto> consents = new ArrayList<>();

    public String init() {


        // prevent creating a user if signup not allowed.
        boolean signupAllowed = settingsService.isTrueForKey(SettingsServiceBean.Key.AllowSignUp);

        if (editMode == EditMode.CREATE && !signupAllowed) {
            return "/403.xhtml";
        }

        if (editMode == EditMode.CREATE) {
            if (isUserAuthenticated()) {
                editMode = null; // we can't be in create mode for an existing user

            } else {
                // in create mode for new user
                userDisplayInfo = new AuthenticatedUserDisplayInfo();
                consents = consentService.prepareConsentsForView(session.getLocale());

                return "";
            }
        }

        if (isUserAuthenticated()) {
            setCurrentUser((AuthenticatedUser) session.getUser());
            userAuthProvider = authenticationService.lookupProvider(currentUser);
            notificationsList = userNotificationDao.findByUser(currentUser.getId());
            preferredNotificationsLanguage = currentUser.getNotificationsLanguage();

            switch (selectTab) {
                case "notifications":
                    activeIndex = 1;
                    displayNotification();
                    break;
                case "dataRelatedToMe":
                    mydatapage.init();
                    break;
                // case "groupsRoles":
                // activeIndex = 2;
                // break;
                case "accountInfo":
                    activeIndex = 2;
                    // activeIndex = 3;
                    break;
                case "apiTokenTab":
                    activeIndex = 3;
                    break;
                default:
                    activeIndex = 0;
                    break;
            }

        } else {
            return permissionsWrapper.notAuthorized();
        }

        return "";
    }

    public boolean isUserAuthenticated() {
        return session.getUser().isAuthenticated();
    }

    public void edit(ActionEvent e) {
        editMode = EditMode.EDIT;
    }

    public void changePassword(ActionEvent e) {
        editMode = EditMode.CHANGE_PASSWORD;
    }

    public void forgotPassword(ActionEvent e) {
        editMode = EditMode.FORGOT;
    }

    public void validateUserName(FacesContext context, UIComponent toValidate, Object value) {
        String userName = (String) value;
        boolean userNameFound = authenticationService.identifierExists(userName);

        // SF fix for issue 3752
        // checks if username has any invalid characters 
        boolean userNameValid = userName != null && UserNameValidator.isUserNameValid(userName, null);

        if (editMode == EditMode.CREATE && userNameFound) {
            ((UIInput) toValidate).setValid(false);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                                                    BundleUtil.getStringFromBundle("user.username.taken"),
                                                    null);
            context.addMessage(toValidate.getClientId(context), message);
        }

        if (editMode == EditMode.CREATE && !userNameValid) {
            ((UIInput) toValidate).setValid(false);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                                                    BundleUtil.getStringFromBundle("user.username.invalid"),
                                                    null);
            context.addMessage(toValidate.getClientId(context), message);
        }
    }

    public void validatePreferredNotificationsLanguage(FacesContext context, UIComponent toValidate, Object value) {
        if (Objects.isNull(value)) {
            ((UIInput) toValidate).setValid(false);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                                                    BundleUtil.getStringFromBundle(
                                                            "user.notificationsLanguage.requiredMessage"),
                                                    null);
            context.addMessage(toValidate.getClientId(context), message);
        }
    }

    public void validateUserEmail(FacesContext context, UIComponent toValidate, Object value) {
        String userEmail = (String) value;
        boolean emailValid = EMailValidator.isEmailValid(userEmail, null);
        if (!emailValid) {
            ((UIInput) toValidate).setValid(false);
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                                                    BundleUtil.getStringFromBundle("oauth2.newAccount.emailInvalid"),
                                                    null);
            context.addMessage(toValidate.getClientId(context), message);
            logger.info("Email is not valid: " + userEmail);
            return;
        }
        boolean userEmailFound = false;
        AuthenticatedUser aUser = authenticationService.getAuthenticatedUserByEmail(userEmail);
        if (editMode == EditMode.CREATE) {
            if (aUser != null) {
                userEmailFound = true;
            }
        } else {

            // In edit mode...
            // if there's a match on edit make sure that the email belongs to the 
            // user doing the editing by checking ids
            if (aUser != null && !aUser.getId().equals(currentUser.getId())) {
                userEmailFound = true;
            }
        }
        if (userEmailFound) {
            ((UIInput) toValidate).setValid(false);

            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                                                    BundleUtil.getStringFromBundle("user.email.taken"),
                                                    null);
            context.addMessage(toValidate.getClientId(context), message);
        }
    }

    public void validateNewPassword(FacesContext context, UIComponent toValidate, Object value) {
        String password = (String) value;
        if (StringUtils.isBlank(password)) {
            logger.log(Level.WARNING, "new password is blank");

            ((UIInput) toValidate).setValid(false);

            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                                                    BundleUtil.getStringFromBundle("passwdVal.passwdReset.valFacesError"),
                                                    BundleUtil.getStringFromBundle(
                                                            "passwdVal.passwdReset.valFacesErrorDesc"));
            context.addMessage(toValidate.getClientId(context), message);
            return;

        }

        final List<String> errors = passwordValidatorService.validate(password, new Date(), false);
        this.passwordErrors = errors;
        if (!errors.isEmpty()) {
            ((UIInput) toValidate).setValid(false);
        }
    }

    public String save() {
        boolean passwordChanged = false;
        if (editMode == EditMode.CHANGE_PASSWORD) {
            final AuthenticationProvider prv = getUserAuthProvider();
            if (prv.isPasswordUpdateAllowed()) {
                if (!prv.verifyPassword(currentUser.getAuthenticatedUserLookup().getPersistentUserId(),
                                        currentPassword)) {
                    FacesContext.getCurrentInstance().addMessage("currentPassword",
                                                                 new FacesMessage(FacesMessage.SEVERITY_ERROR,
                                                                                  BundleUtil.getStringFromBundle(
                                                                                          "user.error.wrongPassword"),
                                                                                  null));
                    return null;
                }
                prv.updatePassword(currentUser.getAuthenticatedUserLookup().getPersistentUserId(), inputPassword);
                passwordChanged = true;

            } else {
                // erroneous state - we can't change the password for this user, so should not have gotten here. Log and bail out.
                logger.log(Level.WARNING,
                           "Attempt to change a password on {0}, whose provider ({1}) does not support password change",
                           new Object[]{currentUser.getIdentifier(), prv});
                JH.addMessage(FacesMessage.SEVERITY_ERROR,
                              BundleUtil.getStringFromBundle("user.error.cannotChangePassword"));
                return null;
            }
        }
        if (editMode == EditMode.CREATE) {
            // Create a new built-in user.
            BuiltinUser builtinUser = new BuiltinUser();
            builtinUser.setUserName(getUsername());
            builtinUser.updateEncryptedPassword(PasswordEncryption.get().encrypt(inputPassword),
                                                PasswordEncryption.getLatestVersionNumber());

            AuthenticatedUser au = authenticationService.createAuthenticatedUser(
                    new UserRecordIdentifier(BuiltinAuthenticationProvider.PROVIDER_ID, builtinUser.getUserName()),
                    builtinUser.getUserName(), userDisplayInfo, false, preferredNotificationsLanguage).getOrNull();
            if (au == null) {
                // Username already exists, show an error message
                getUsernameField().setValid(false);
                FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                                                        BundleUtil.getStringFromBundle("user.username.taken"),
                                                        null);
                FacesContext context = FacesContext.getCurrentInstance();
                context.addMessage(getUsernameField().getClientId(context), message);
                return null;
            }

            // The Authenticated User was just created via the UI, add an initial login timestamp
            au = userService.updateLastLogin(au);

            // Authenticated user registered. Save the new bulitin, and log in.
            builtinUserService.save(builtinUser);
            session.setUser(au);
            /**
             * @todo Move this to
             * AuthenticationServiceBean.createAuthenticatedUser
             */
            userNotificationService.sendNotificationWithEmail(au, new Timestamp(new Date().getTime()),
                    NotificationType.CREATEACC, null, NotificationObjectType.AUTHENTICATED_USER);

            consentService.executeActionsAndSaveAcceptedConsents(consents, au);
            // go back to where user came from

            // (but if they came from the login page, then send them to the 
            // root dataverse page instead. the only situation where we do 
            // want to send them back to the login page is if they hit 
            // 'cancel'. 

            if ("/loginpage.xhtml".equals(redirectPage) || "loginpage.xhtml".equals(redirectPage)) {
                redirectPage = "/dataverse.xhtml";
            }

            if ("dataverse.xhtml".equals(redirectPage)) {
                redirectPage = redirectPage + "?alias=" + dataverseDao.findRootDataverse().getAlias();
            }

            try {
                redirectPage = URLDecoder.decode(redirectPage, "UTF-8");
            } catch (UnsupportedEncodingException ex) {
                logger.log(Level.SEVERE, "Server does not support 'UTF-8' encoding.", ex);
                redirectPage = "dataverse.xhtml?alias=" + dataverseDao.findRootDataverse().getAlias();
            }

            logger.log(Level.FINE, "Sending user to = {0}", redirectPage);


            return redirectPage + (!redirectPage.contains("?") ? "?" : "&") + "faces-redirect=true";

            //Happens if user is logged out while editing
        } else if (!isUserAuthenticated()) {
            logger.info("Redirecting");
            return permissionsWrapper.notAuthorized() + "faces-redirect=true";
        } else {
            String emailBeforeUpdate = currentUser.getEmail();
            AuthenticatedUser savedUser = authenticationService.updateAuthenticatedUser(currentUser,
                                                                                        userDisplayInfo,
                                                                                        preferredNotificationsLanguage);
            String emailAfterUpdate = savedUser.getEmail();
            editMode = null;
            StringBuilder msg = new StringBuilder(passwordChanged ?
                                                          BundleUtil.getStringFromBundle("userPage.passwordChanged")
                                                          :
                                                          BundleUtil.getStringFromBundle("userPage.informationUpdated"));
            if (!emailBeforeUpdate.equals(emailAfterUpdate)) {
                String expTime = ConfirmEmailUtil.friendlyExpirationTime(settingsService.getValueForKeyAsLong(
                        SettingsServiceBean.Key.MinutesUntilConfirmEmailTokenExpires));
                List<String> args = Arrays.asList(currentUser.getEmail(), expTime);
                // delete unexpired token, if it exists (clean slate)
                confirmEmailService.deleteTokenForUser(currentUser);
                try {
                    confirmEmailService.beginConfirm(currentUser);
                } catch (ConfirmEmailException ex) {
                    logger.log(Level.INFO, "Unable to send email confirmation link to user id {0}", savedUser.getId());
                }
                session.setUser(currentUser);
                JsfHelper.addFlashSuccessMessage(BundleUtil.getStringFromBundle("confirmEmail.changed", args));
            } else {
                JsfHelper.addFlashMessage(msg.toString());
            }
            return null;
        }
    }

    public String cancel() {
        if (editMode == EditMode.CREATE) {
            return "/dataverse.xhtml?alias=" + dataverseDao.findRootDataverse().getAlias() + "&faces-redirect=true";
        }

        editMode = null;
        return null;
    }

    public String remove(Long notificationId) {
        UserNotification userNotification = userNotificationDao.find(notificationId);
        userNotificationDao.delete(userNotification);
        for (UserNotification uNotification : notificationsList) {
            if (Objects.equals(uNotification.getId(), userNotification.getId())) {
                notificationsList.remove(uNotification);
                break;
            }
        }
        return null;
    }

    public void onTabChange(TabChangeEvent event) {
        if (event.getTab().getId().equals("notifications")) {
            displayNotification();
        }
        if (event.getTab().getId().equals("dataRelatedToMe")) {
            mydatapage.init();
        }
    }

    private String getRoleStringFromUser(AuthenticatedUser au, DvObject dvObj) {
        // Find user's role(s) for given dataverse/dataset
        Set<RoleAssignment> roles = permissionService.assignmentsFor(au, dvObj);
        List<String> roleNames = new ArrayList<>();

        // Include roles derived from a user's groups
        Set<Group> groupsUserBelongsTo = groupService.groupsFor(au, dvObj);
        for (Group g : groupsUserBelongsTo) {
            roles.addAll(permissionService.assignmentsFor(g, dvObj));
        }

        for (RoleAssignment ra : roles) {
            roleNames.add(ra.getRole().getName());
        }
        if (roleNames.isEmpty()) {
            return "[Unknown]";
        }
        return StringUtils.join(roleNames, "/");
    }

    public void displayNotification() {
        for (UserNotification userNotification : notificationsList) {
            switch (userNotification.getType()) {
                case ASSIGNROLE:
                case REVOKEROLE:
                    // Can either be a dataverse or dataset, so search both
                    Dataverse dataverse = dataverseDao.find(userNotification.getObjectId());
                    if (dataverse != null) {
                        userNotification.setRoleString(this.getRoleStringFromUser(this.getCurrentUser(), dataverse));
                        userNotification.setTheObject(dataverse);
                    } else {
                        Dataset dataset = datasetDao.find(userNotification.getObjectId());
                        if (dataset != null) {
                            userNotification.setRoleString(this.getRoleStringFromUser(this.getCurrentUser(), dataset));
                            userNotification.setTheObject(dataset);
                        } else {
                            DataFile datafile = fileService.find(userNotification.getObjectId());
                            userNotification.setRoleString(this.getRoleStringFromUser(this.getCurrentUser(), datafile));
                            userNotification.setTheObject(datafile);
                        }
                    }
                    break;
                case CREATEDV:
                    userNotification.setTheObject(dataverseDao.find(userNotification.getObjectId()));
                    break;

                case REQUESTFILEACCESS:
                    DataFile file = fileService.find(userNotification.getObjectId());
                    userNotification.setTheObject(file.getOwner());
                    break;
                case GRANTFILEACCESS:
                case REJECTFILEACCESS:
                    userNotification.setTheObject(datasetDao.find(userNotification.getObjectId()));
                    break;

                case MAPLAYERUPDATED:
                case CREATEDS:
                case SUBMITTEDDS:
                case PUBLISHEDDS:
                case RETURNEDDS:
                    userNotification.setTheObject(datasetVersionService.find(userNotification.getObjectId()));
                    break;

                case MAPLAYERDELETEFAILED:
                    userNotification.setTheObject(fileService.findFileMetadata(userNotification.getObjectId()));
                    break;

                case CREATEACC:
                    userNotification.setTheObject(userNotification.getUser());
                    break;

                case CHECKSUMFAIL:
                    userNotification.setTheObject(datasetDao.find(userNotification.getObjectId()));
                    break;

                case FILESYSTEMIMPORT:
                    userNotification.setTheObject(datasetVersionService.find(userNotification.getObjectId()));
                    break;

                case CHECKSUMIMPORT:
                    userNotification.setTheObject(datasetVersionService.find(userNotification.getObjectId()));
                    break;
            }

            userNotification.setDisplayAsRead(userNotification.isReadNotification());
            if (userNotification.isReadNotification() == false) {
                userNotification.setReadNotification(true);
                userNotificationDao.merge(userNotification);
            }
        }
    }

    public void sendConfirmEmail() {
        logger.fine("called sendConfirmEmail()");
        String userEmail = currentUser.getEmail();

        try {
            confirmEmailService.beginConfirm(currentUser);
            List<String> args = Arrays.asList(
                    userEmail,
                    ConfirmEmailUtil.friendlyExpirationTime(settingsService.getValueForKeyAsLong(SettingsServiceBean.Key.MinutesUntilConfirmEmailTokenExpires)));
            JsfHelper.addFlashSuccessMessage(BundleUtil.getStringFromBundle("confirmEmail.submitRequest.success",
                                                                            args));
        } catch (ConfirmEmailException ex) {
            Logger.getLogger(DataverseUserPage.class.getName()).log(Level.SEVERE, null, ex);
        }
    }


    /**
     * Determines whether the button to send a verification email appears on user page
     *
     * @return
     */
    public boolean showVerifyEmailButton() {
        final Timestamp emailConfirmed = currentUser.getEmailConfirmed();
        final ConfirmEmailData confirmedDate = confirmEmailService.findSingleConfirmEmailDataByUser(currentUser);
        return (!getUserAuthProvider().isEmailVerified())
                && confirmedDate == null
                && emailConfirmed == null;
    }

    public boolean isEmailIsVerified() {

        return currentUser.getEmailConfirmed() != null &&
                confirmEmailService.findSingleConfirmEmailDataByUser(currentUser) == null;
    }

    public boolean isEmailNotVerified() {
        return currentUser.getEmailConfirmed() == null ||
                confirmEmailService.findSingleConfirmEmailDataByUser(currentUser) != null;
    }

    public boolean isEmailGrandfathered() {
        return currentUser.getEmailConfirmed().equals(ConfirmEmailUtil.getGrandfatheredTime());
    }

    public AuthenticationProvider getUserAuthProvider() {
        if (userAuthProvider == null) {
            userAuthProvider = authenticationService.lookupProvider(currentUser);
        }
        return userAuthProvider;
    }

    public String getUserLocalizedNotificationsLanguageForDisplay() {
        String displayLanguage = StringUtils
                .capitalize(currentUser.getNotificationsLanguage().getDisplayLanguage(session.getLocale()));

        return isUserLanguageConfigured() ?
                displayLanguage :
                displayLanguage + " " + BundleUtil.getStringFromBundle("user.notificationsLanguage.notSupported");
    }


    public boolean isPasswordEditable() {
        return getUserAuthProvider().isPasswordUpdateAllowed();
    }

    public boolean isAccountDetailsEditable() {
        return getUserAuthProvider().isUserInfoUpdateAllowed();
    }

    public AuthenticatedUserDisplayInfo getUserDisplayInfo() {
        return userDisplayInfo;
    }

    public void setUserDisplayInfo(AuthenticatedUserDisplayInfo userDisplayInfo) {
        this.userDisplayInfo = userDisplayInfo;
    }

    public EditMode getChangePasswordMode() {
        return EditMode.CHANGE_PASSWORD;
    }

    public AuthenticatedUser getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(AuthenticatedUser currentUser) {
        this.currentUser = currentUser;
        userDisplayInfo = currentUser.getDisplayInfo();
        username = currentUser.getUserIdentifier();
    }

    public EditMode getEditMode() {
        return editMode;
    }

    public void setEditMode(EditMode editMode) {
        this.editMode = editMode;
    }

    public String getRedirectPage() {
        return redirectPage;
    }

    public void setRedirectPage(String redirectPage) {
        this.redirectPage = redirectPage;
    }

    public String getInputPassword() {
        return inputPassword;
    }

    public void setInputPassword(String inputPassword) {
        this.inputPassword = inputPassword;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public Long getDataverseId() {

        if (dataverseId == null) {
            dataverseId = dataverseDao.findRootDataverse().getId();
        }
        return dataverseId;
    }

    public void setDataverseId(Long dataverseId) {
        this.dataverseId = dataverseId;
    }

    public List getNotificationsList() {
        return notificationsList;
    }

    public void setNotificationsList(List<UserNotification> notificationsList) {
        this.notificationsList = notificationsList;
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public void setActiveIndex(int activeIndex) {
        this.activeIndex = activeIndex;
    }

    public String getSelectTab() {
        return selectTab;
    }

    public void setSelectTab(String selectTab) {
        this.selectTab = selectTab;
    }

    public UIInput getUsernameField() {
        return usernameField;
    }

    public void setUsernameField(UIInput usernameField) {
        this.usernameField = usernameField;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isNonLocalLoginEnabled() {
        return AuthUtil.isNonLocalLoginEnabled(authenticationService.getAuthenticationProviders());
    }

    public String getAdditionalMessage(UserNotification notification) {
        return notification.getAdditionalMessage();
    }

    public String getPasswordRequirements() {
        return passwordValidatorService.getGoodPasswordDescription(passwordErrors);
    }

    public String getRequestorName(UserNotification notification) {
        if (notification == null) {
            return BundleUtil.getStringFromBundle("notification.email.info.unavailable");
        }
        if (notification.getRequestor() == null) {
            return BundleUtil.getStringFromBundle("notification.email.info.unavailable");
        }
        return (notification.getRequestor().getLastName() != null && notification.getRequestor().getLastName() != null) ?
                notification.getRequestor().getFirstName() + " " + notification.getRequestor().getLastName() :
                BundleUtil.getStringFromBundle("notification.email.info.unavailable");
    }

    public String getRequestorEmail(UserNotification notification) {
        if (notification == null) {
            return BundleUtil.getStringFromBundle("notification.email.info.unavailable");
        }
        if (notification.getRequestor() == null) {
            return BundleUtil.getStringFromBundle("notification.email.info.unavailable");
        }
        return notification.getRequestor().getEmail() != null ?
                notification.getRequestor().getEmail() :
                BundleUtil.getStringFromBundle("notification.email.info.unavailable");
    }

    public List<String> getSupportedLanguages() {
        return new ArrayList<>(settingsWrapper.getConfiguredLocales().keySet());
    }

    public String getPreferredNotificationsLanguage() {
        return Option.of(preferredNotificationsLanguage)
                    .map(locale -> locale.getLanguage())
                    .getOrNull();
    }

    public String getLocalizedPreferredNotificationsLanguage() {
        return getLocalizedDisplayNameForLanguage(preferredNotificationsLanguage);
    }

    public String getLocalizedDisplayNameForLanguage(String language) {
        return getLocalizedDisplayNameForLanguage(Locale.forLanguageTag(language));
    }

    public List<ConsentDto> getConsents() {
        return consents;
    }

    // -------------------- PRIVATE ---------------------

    private boolean isUserLanguageConfigured() {
        return StringUtils.isNotEmpty(settingsWrapper.getConfiguredLocaleName(currentUser.getNotificationsLanguage().toLanguageTag()));
    }

    private String getLocalizedDisplayNameForLanguage(Locale language) {
        return language.getDisplayName(session.getLocale());
    }

    // -------------------- SETTERS --------------------

    public void setPreferredNotificationsLanguage(String preferredNotificationsLanguage) {
        this.preferredNotificationsLanguage = Locale.forLanguageTag(preferredNotificationsLanguage);
    }
}