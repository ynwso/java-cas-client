/*
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.client.session;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.jasig.cas.client.util.CommonUtils;
import org.jasig.cas.client.util.XmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs CAS single sign-out operations in an API-agnostic fashion.
 *
 * @author Marvin S. Addison
 * @version $Revision$ $Date$
 * @since 3.1.12
 *
 */
public final class SingleSignOutHandler {

    public final static String DEFAULT_ARTIFACT_PARAMETER_NAME = "ticket";
    public final static String DEFAULT_LOGOUT_PARAMETER_NAME = "logoutRequest";
    public final static String DEFAULT_FRONT_LOGOUT_PARAMETER_NAME = "SAMLRequest";
    public final static String DEFAULT_RELAY_STATE_PARAMETER_NAME = "RelayState";
    
    /** Logger instance */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /** Mapping of token IDs and session IDs to HTTP sessions */
    private SessionMappingStorage sessionMappingStorage = new HashMapBackedSessionMappingStorage();

    /** The name of the artifact parameter.  This is used to capture the session identifier. */
    private String artifactParameterName = DEFAULT_ARTIFACT_PARAMETER_NAME;

    /** Parameter name that stores logout request for back channel SLO */
    private String logoutParameterName = DEFAULT_LOGOUT_PARAMETER_NAME;

    /** Parameter name that stores logout request for front channel SLO */
    private String frontLogoutParameterName = DEFAULT_FRONT_LOGOUT_PARAMETER_NAME;

    /** Parameter name that stores the state of the CAS server webflow for the callback */
    private String relayStateParameterName = DEFAULT_RELAY_STATE_PARAMETER_NAME;
    
    /** The prefix url of the CAS server */
    private String casServerUrlPrefix;

    private boolean artifactParameterOverPost = false;

    private boolean eagerlyCreateSessions = true;

    private List<String> safeParameters;

    public void setSessionMappingStorage(final SessionMappingStorage storage) {
        this.sessionMappingStorage = storage;
    }

    public void setArtifactParameterOverPost(final boolean artifactParameterOverPost) {
        this.artifactParameterOverPost = artifactParameterOverPost;
    }

    public SessionMappingStorage getSessionMappingStorage() {
        return this.sessionMappingStorage;
    }

    /**
     * @param name Name of the authentication token parameter.
     */
    public void setArtifactParameterName(final String name) {
        this.artifactParameterName = name;
    }

    /**
     * @param name Name of parameter containing CAS logout request message for back channel SLO.
     */
    public void setLogoutParameterName(final String name) {
        this.logoutParameterName = name;
    }

    /**
     * @param casServerUrlPrefix The prefix url of the CAS server.
     */
    public void setCasServerUrlPrefix(final String casServerUrlPrefix) {
        this.casServerUrlPrefix = casServerUrlPrefix;
    }

    /**
     * @param name Name of parameter containing CAS logout request message for front channel SLO.
     */
    public void setFrontLogoutParameterName(final String name) {
        this.frontLogoutParameterName = name;
    }

    /**
     * @param name Name of parameter containing the state of the CAS server webflow.
     */
    public void setRelayStateParameterName(final String name) {
        this.relayStateParameterName = name;
    }

    public void setEagerlyCreateSessions(final boolean eagerlyCreateSessions) {
        this.eagerlyCreateSessions = eagerlyCreateSessions;
    }

    /**
     * Initializes the component for use.
     */
    public void init() {
        CommonUtils.assertNotNull(this.artifactParameterName, "artifactParameterName cannot be null.");
        CommonUtils.assertNotNull(this.logoutParameterName, "logoutParameterName cannot be null.");
        CommonUtils.assertNotNull(this.frontLogoutParameterName, "frontLogoutParameterName cannot be null.");
        CommonUtils.assertNotNull(this.sessionMappingStorage, "sessionMappingStorage cannot be null.");
        CommonUtils.assertNotNull(this.relayStateParameterName, "relayStateParameterName cannot be null.");
        CommonUtils.assertNotNull(this.casServerUrlPrefix, "casServerUrlPrefix cannot be null.");

        if (this.artifactParameterOverPost) {
            this.safeParameters = Arrays.asList(this.logoutParameterName, this.artifactParameterName);
        } else {
            this.safeParameters = Arrays.asList(this.logoutParameterName);
        }
    }

    /**
     * Determines whether the given request contains an authentication token.
     *
     * @param request HTTP reqest.
     *
     * @return True if request contains authentication token, false otherwise.
     */
    protected boolean isTokenRequest(final HttpServletRequest request) {
        return CommonUtils.isNotBlank(CommonUtils.safeGetParameter(request, this.artifactParameterName,
                this.safeParameters));
    }

    /**
     * Determines whether the given request is a CAS back channel logout request.
     *
     * @param request HTTP request.
     *
     * @return True if request is logout request, false otherwise.
     */
    protected boolean isBackChannelLogoutRequest(final HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && !isMultipartRequest(request)
                && CommonUtils.isNotBlank(CommonUtils.safeGetParameter(request, this.logoutParameterName,
                        this.safeParameters));
    }

    /**
     * Determines whether the given request is a CAS front channel logout request.
     *
     * @param request HTTP request.
     *
     * @return True if request is logout request, false otherwise.
     */
    protected boolean isFrontChannelLogoutRequest(final HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && CommonUtils.isNotBlank(CommonUtils.safeGetParameter(request, this.frontLogoutParameterName));
    }

    /**
     * Process a request regarding the SLO process: record the session or destroy it.
     *
     * @param request the incoming HTTP request.
     * @param response the HTTP response.
     * @return if the request should continue to be processed.
     */
    public boolean process(final HttpServletRequest request, final HttpServletResponse response) {
        if (isTokenRequest(request)) {
            recordSession(request);
        } else if (isBackChannelLogoutRequest(request)) {
            destroySession(request);
            // Do not continue up filter chain
            return false;
        } else if (isFrontChannelLogoutRequest(request)) {
            destroySession(request);
            // redirection url to the CAS server
            final String redirectionUrl = computeRedirectionToServer(request);
            if (redirectionUrl != null) {
                CommonUtils.sendRedirect(response, redirectionUrl);
            }
            return false;
        } else {
            logger.trace("Ignoring URI {}", request.getRequestURI());
        }
        return true;
    }

    /**
     * Associates a token request with the current HTTP session by recording the mapping
     * in the the configured {@link SessionMappingStorage} container.
     * 
     * @param request HTTP request containing an authentication token.
     */
    protected void recordSession(final HttpServletRequest request) {
        final HttpSession session = request.getSession(this.eagerlyCreateSessions);

        if (session == null) {
            logger.debug("No session currently exists (and none created).  Cannot record session information for single sign out.");
            return;
        }

        final String token = CommonUtils.safeGetParameter(request, this.artifactParameterName, this.safeParameters);
        logger.debug("Recording session for token {}", token);

        try {
            this.sessionMappingStorage.removeBySessionById(session.getId());
        } catch (final Exception e) {
            // ignore if the session is already marked as invalid.  Nothing we can do!
        }
        sessionMappingStorage.addSessionById(token, session);
    }

    /**
     * Uncompress a logout message (base64 + deflate).
     * 
     * @param originalMessage the original logout message.
     * @return the uncompressed logout message.
     */
    protected String uncompressLogoutMessage(final String originalMessage) {
        // base64 decode
        final byte[] binaryMessage = Base64.decodeBase64(originalMessage);

        Inflater decompresser = null;
        try {
            // decompress the bytes
            decompresser = new Inflater();
            decompresser.setInput(binaryMessage);

            /* The received logout message is compressed, so this number (10) is the multiplier of the original size
             * of the logout message (binaryMessage.length) to compute the size of the buffer where the logout message
             * will be decompressed.
             * It's somehow the decompression factor.
             *
             * For the buffer, we could also have a fixed size for the buffer (like 10k), but I thought that ten times
             * would be a sufficient multiplier...
             *
             * A real test:
             *  String sessionIndex = "ST-45-fs45646r84ffs1d31f554f5d4f64fg6r8eq5s4d6f4fddsf46-cas";
             *  String bm = LogoutMessageGenerator.generateBackChannelLogoutMessage(sessionIndex);
             *  System.out.println("bm.size = " + bm.length());
             *  String fm = new String(Base64.decodeBase64(LogoutMessageGenerator.
             *                                                 generateFrontChannelLogoutMessage(sessionIndex)));
             *  System.out.println("fm.size = " + fm.length());
             *
             * And the result:
             *  bm.size = 354
             *  fm.size = 224
             *
             * So ten times is enough, it's even too much...
             */
            byte[] result = new byte[binaryMessage.length * 10];

            int resultLength = decompresser.inflate(result);

            // decode the bytes into a String
            return new String(result, 0, resultLength, "UTF-8");
        } catch (DataFormatException e) {
            logger.error("Unable to decompress logout message", e);
            throw new RuntimeException(e);
        } catch (UnsupportedEncodingException e) {
            logger.error("Unable to decompress logout message", e);
            throw new RuntimeException(e);
        } finally {
            if (decompresser != null) {
                decompresser.end();
            }
        }
    }

    /**
     * Destroys the current HTTP session for the given CAS logout request.
     *
     * @param request HTTP request containing a CAS logout message.
     */
    protected void destroySession(final HttpServletRequest request) {
        String logoutMessage;
        // front channel logout -> the message needs to be base64 decoded + decompressed
        if ("GET".equals(request.getMethod())) {
            logoutMessage = uncompressLogoutMessage(CommonUtils.safeGetParameter(request,
                    this.frontLogoutParameterName));
        } else {
            logoutMessage = CommonUtils.safeGetParameter(request, this.logoutParameterName, this.safeParameters);
        }
        logger.trace("Logout request:\n{}", logoutMessage);

        final String token = XmlUtils.getTextForElement(logoutMessage, "SessionIndex");
        if (CommonUtils.isNotBlank(token)) {
            final HttpSession session = this.sessionMappingStorage.removeSessionByMappingId(token);

            if (session != null) {
                String sessionID = session.getId();

                logger.debug("Invalidating session [{}] for token [{}]", sessionID, token);

                try {
                    session.invalidate();
                } catch (final IllegalStateException e) {
                    logger.debug("Error invalidating session.", e);
                }
                try {
                    request.logout();
                } catch (final ServletException e) {
                    logger.debug("Error performing request.logout.");
                }
            }
        }
    }

    /**
     * Compute the redirection url to the CAS server when it's a front channel SLO
     * (depending on the relay state parameter).
     *
     * @param request The HTTP request.
     * @return the redirection url to the CAS server.
     */
    protected String computeRedirectionToServer(final HttpServletRequest request) {
        // relay state value
        final String relayStateValue = CommonUtils.safeGetParameter(request, this.relayStateParameterName);
        // if we have a state value -> redirect to the CAS server to continue the logout process
        if (StringUtils.isNotBlank(relayStateValue)) {
            final StringBuffer buffer = new StringBuffer();
            buffer.append(casServerUrlPrefix);
            if (!this.casServerUrlPrefix.endsWith("/")) {
                buffer.append("/");
            }
            buffer.append("logout?_eventId=next&");
            buffer.append(this.relayStateParameterName);
            buffer.append("=");
            buffer.append(CommonUtils.urlEncode(relayStateValue));
            final String redirectUrl = buffer.toString();
            logger.debug("Redirection url to the CAS server: {}", redirectUrl);
            return redirectUrl;
        }
        return null;
    }

    private boolean isMultipartRequest(final HttpServletRequest request) {
        return request.getContentType() != null && request.getContentType().toLowerCase().startsWith("multipart");
    }
}
