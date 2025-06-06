/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2023 Daniel Raper (me@danr.uk)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.database;

import com.google.inject.Inject;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.AuthorizationResponse;
import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.util.URLUtils;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import com.nimbusds.openid.connect.sdk.UserInfoResponse;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import jakarta.servlet.http.HttpServletRequest;
import org.traccar.api.security.LoginService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.LogAction;
import org.traccar.helper.SessionHelper;
import org.traccar.helper.WebHelper;
import org.traccar.model.User;
import org.traccar.storage.StorageException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.util.List;

public class OpenIdProvider {
    private final Boolean force;
    private final ClientID clientId;
    private final ClientAuthentication clientAuth;
    private final URI callbackUrl;
    private final URI authUrl;
    private final URI tokenUrl;
    private final URI userInfoUrl;
    private final URI baseUrl;
    private final String adminGroup;
    private final String allowGroup;
    private final String groupsClaimName;

    private final LoginService loginService;
    private final LogAction actionLogger;

    @Inject
    public OpenIdProvider(
            Config config, LoginService loginService, LogAction actionLogger)
            throws IOException, URISyntaxException, GeneralException {

        this.loginService = loginService;
        this.actionLogger = actionLogger;

        force = config.getBoolean(Keys.OPENID_FORCE);
        clientId = new ClientID(config.getString(Keys.OPENID_CLIENT_ID));
        clientAuth = new ClientSecretBasic(clientId, new Secret(config.getString(Keys.OPENID_CLIENT_SECRET)));

        baseUrl = URI.create(WebHelper.retrieveWebUrl(config));
        callbackUrl = URI.create(WebHelper.retrieveWebUrl(config) + "/api/session/openid/callback");

        if (config.hasKey(Keys.OPENID_ISSUER_URL)) {
            OIDCProviderMetadata meta = OIDCProviderMetadata.resolve(
                    new Issuer(config.getString(Keys.OPENID_ISSUER_URL)));
            authUrl = meta.getAuthorizationEndpointURI();
            tokenUrl = meta.getTokenEndpointURI();
            userInfoUrl = meta.getUserInfoEndpointURI();
        } else {
            authUrl = new URI(config.getString(Keys.OPENID_AUTH_URL));
            tokenUrl = new URI(config.getString(Keys.OPENID_TOKEN_URL));
            userInfoUrl = new URI(config.getString(Keys.OPENID_USERINFO_URL));
        }

        adminGroup = config.getString(Keys.OPENID_ADMIN_GROUP);
        allowGroup = config.getString(Keys.OPENID_ALLOW_GROUP);
        groupsClaimName = config.getString(Keys.OPENID_GROUPS_CLAIM_NAME);
    }

    public URI createAuthUri() {
        Scope scope = new Scope("openid", "profile", "email");
        if (adminGroup != null) {
            scope.add(groupsClaimName);
        }
        return new AuthenticationRequest.Builder(new ResponseType("code"), scope, clientId, callbackUrl)
                .endpointURI(authUrl)
                .state(new State())
                .build()
                .toURI();
    }

    private OIDCTokenResponse getToken(
            URI redirectUri, AuthorizationCode code) throws IOException, ParseException, GeneralSecurityException {

        AuthorizationGrant codeGrant = new AuthorizationCodeGrant(code, redirectUri);
        TokenRequest tokenRequest = new TokenRequest(tokenUrl, clientAuth, codeGrant, null);

        HTTPResponse tokenResponse = tokenRequest.toHTTPRequest().send();
        TokenResponse token = OIDCTokenResponseParser.parse(tokenResponse);
        if (!token.indicatesSuccess()) {
            throw new GeneralSecurityException("Unable to authenticate with the OpenID Connect provider");
        }

        return (OIDCTokenResponse) token.toSuccessResponse();
    }

    private UserInfo getUserInfo(
            BearerAccessToken token) throws IOException, ParseException, GeneralSecurityException {

        HTTPResponse httpResponse = new UserInfoRequest(userInfoUrl, token)
                .toHTTPRequest()
                .send();

        UserInfoResponse userInfoResponse = UserInfoResponse.parse(httpResponse);

        if (!userInfoResponse.indicatesSuccess()) {
            throw new GeneralSecurityException("Failed to access OpenID Connect user info endpoint");
        }

        return userInfoResponse.toSuccessResponse().getUserInfo();
    }

    public URI handleCallback(String queryParameters, HttpServletRequest request)
            throws StorageException, ParseException, IOException, GeneralSecurityException {

        String redirectUriOverride = request.getParameter("redirect_uri");
        URI redirectUri = redirectUriOverride != null ? URI.create(redirectUriOverride) : callbackUrl;
        AuthorizationResponse response = AuthorizationResponse.parse(
                redirectUri, URLUtils.parseParameters(queryParameters));

        if (!response.indicatesSuccess()) {
            throw new GeneralSecurityException(response.toErrorResponse().getErrorObject().getDescription());
        }

        AuthorizationCode authCode = response.toSuccessResponse().getAuthorizationCode();
        if (authCode == null) {
            throw new GeneralSecurityException("Malformed OpenID callback");
        }

        OIDCTokenResponse tokens = getToken(redirectUri, authCode);

        BearerAccessToken bearerToken = tokens.getOIDCTokens().getBearerAccessToken();

        UserInfo userInfo = getUserInfo(bearerToken);

        List<String> userGroups = userInfo.getStringListClaim(groupsClaimName);
        boolean administrator = adminGroup != null && userGroups.contains(adminGroup);

        if (!(administrator || allowGroup == null || userGroups.contains(allowGroup))) {
            throw new GeneralSecurityException("Your OpenID Groups do not permit access");
        }

        User user = loginService.login(
                userInfo.getEmailAddress(), userInfo.getName(), administrator).getUser();

        SessionHelper.userLogin(actionLogger, request, user, null);

        return baseUrl.resolve("?openid=success");
    }

    public boolean getForce() {
        return force;
    }
}
