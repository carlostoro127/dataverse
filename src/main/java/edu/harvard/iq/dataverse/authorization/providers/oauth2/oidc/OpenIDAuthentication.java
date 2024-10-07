package edu.harvard.iq.dataverse.authorization.providers.oauth2.oidc;

import java.io.IOException;

import edu.harvard.iq.dataverse.util.SystemConfig;
import fish.payara.security.annotations.LogoutDefinition;
import fish.payara.security.annotations.OpenIdAuthenticationDefinition;
import fish.payara.security.openid.api.OpenIdConstant;
import jakarta.annotation.security.DeclareRoles;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OIDC login implementation
 */
@WebServlet("/oidc/login")
@OpenIdAuthenticationDefinition(
    providerURI = "#{openIdConfigBean.providerURI}",
    clientId = "#{openIdConfigBean.clientId}",
    clientSecret = "#{openIdConfigBean.clientSecret}",
    redirectURI = "#{openIdConfigBean.redirectURI}",
    logout = @LogoutDefinition(redirectURI = "#{openIdConfigBean.logoutURI}"),
    scope = {OpenIdConstant.OPENID_SCOPE, OpenIdConstant.EMAIL_SCOPE, OpenIdConstant.PROFILE_SCOPE},
    useSession = true // If enabled state & nonce value stored in session otherwise in cookies.
)
@DeclareRoles("nobodyHasAccess")
@ServletSecurity(@HttpConstraint(rolesAllowed = "nobodyHasAccess"))
public class OpenIDAuthentication extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        final String baseURL = SystemConfig.getDataverseSiteUrlStatic();
        final String target = request.getParameter("target");
        final String redirect = "SPA".equals(target) ? baseURL + "/spa/" : baseURL;
        response.sendRedirect(redirect);
    }
}
