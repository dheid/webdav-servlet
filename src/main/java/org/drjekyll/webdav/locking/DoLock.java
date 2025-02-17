/*
 * Copyright 1999,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drjekyll.webdav.locking;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import lombok.extern.slf4j.Slf4j;
import org.drjekyll.webdav.Transaction;
import org.drjekyll.webdav.WebdavStatus;
import org.drjekyll.webdav.XMLWriter;
import org.drjekyll.webdav.exceptions.WebdavException;
import org.drjekyll.webdav.methods.Method;
import org.drjekyll.webdav.store.StoredObject;
import org.drjekyll.webdav.store.WebdavStore;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

@Slf4j
public class DoLock extends Method {

    private final WebdavStore store;

    private final IResourceLocks resourcelocks;

    private final boolean readOnly;

    private boolean macLockRequest;

    private boolean exclusive;

    private String type;

    private String lockOwner;

    private String path;

    private String parentPath;

    private String userAgent;

    public DoLock(
        WebdavStore store, IResourceLocks resourceLocks, boolean readOnly
    ) {
        this.store = store;
        resourcelocks = resourceLocks;
        this.readOnly = readOnly;
    }

    @Override
    public void execute(
        Transaction transaction, HttpServletRequest req, HttpServletResponse resp
    ) throws IOException {
        log.trace("-- {}", getClass().getName());

        if (readOnly) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        path = getRelativePath(req);
        parentPath = getParentPath(getCleanPath(path));

        if (!checkLocks(transaction, req, resourcelocks, path)) {
            resp.setStatus(WebdavStatus.SC_LOCKED);
            return; // resource is locked
        }

        if (!checkLocks(transaction, req, resourcelocks, parentPath)) {
            resp.setStatus(WebdavStatus.SC_LOCKED);
            return; // parent is locked
        }

        // Mac OS Finder (whether 10.4.x or 10.5) can't store files
        // because executing a LOCK without lock information causes a
        // SC_BAD_REQUEST
        userAgent = req.getHeader("User-Agent");
        if (userAgent != null && userAgent.contains("Darwin")) {
            macLockRequest = true;

            String timeString = Long.toString(System.currentTimeMillis());
            lockOwner = userAgent + timeString;
        }

        String tempLockOwner = "doLock" + System.currentTimeMillis() + req;
        if (resourcelocks.lock(transaction,
            path,
            tempLockOwner,
            false,
            0,
            TEMP_TIMEOUT,
            TEMPORARY
        )) {
            try {
                if (req.getHeader("If") != null) {
                    doRefreshLock(transaction, req, resp);
                } else {
                    doLock(transaction, req, resp);
                }
            } catch (LockFailedException e) {
                resp.sendError(WebdavStatus.SC_LOCKED);
                log.error("Lockfailed exception", e);
            } finally {
                resourcelocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
            }
        }
    }

    private void doLock(
        Transaction transaction, HttpServletRequest req, HttpServletResponse resp
    ) throws IOException {

        StoredObject so = store.getStoredObject(transaction, path);

        if (so != null) {
            doLocking(transaction, req, resp);
        } else {
            // resource doesn't exist, null-resource lock
            doNullResourceLock(transaction, req, resp);
        }

        exclusive = false;
        type = null;
        lockOwner = null;

    }

    private void doLocking(
        Transaction transaction, HttpServletRequest req, HttpServletResponse resp
    ) throws IOException {

        // Tests if LockObject on requested path exists, and if so, tests
        // exclusivity
        LockedObject lo = resourcelocks.getLockedObjectByPath(transaction, path);
        if (lo != null) {
            if (lo.isExclusive()) {
                sendLockFailError(req, resp);
                return;
            }
        }
        try {
            // Thats the locking itself
            executeLock(transaction, req, resp);

        } catch (ServletException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.trace(e.toString());
        } catch (LockFailedException e) {
            sendLockFailError(req, resp);
        }

    }

    private void doNullResourceLock(
        Transaction transaction, HttpServletRequest req, HttpServletResponse resp
    ) throws IOException {

        StoredObject parentSo;
        StoredObject nullSo;

        try {
            parentSo = store.getStoredObject(transaction, parentPath);
            if (parentPath != null && parentSo == null) {
                store.createFolder(transaction, parentPath);
            } else if (parentPath != null && parentSo.isResource()) {
                resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
                return;
            }

            nullSo = store.getStoredObject(transaction, path);
            if (nullSo == null) {
                // resource doesn't exist
                store.createResource(transaction, path);

                // Transmit expects 204 response-code, not 201
                if (userAgent != null && userAgent.contains("Transmit")) {
                    log.trace("DoLock.execute() : do workaround for user agent '{}'", userAgent);
                    resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                } else {
                    resp.setStatus(HttpServletResponse.SC_CREATED);
                }

            } else {
                // resource already exists, could not execute null-resource lock
                sendLockFailError(req, resp);
                return;
            }
            nullSo = store.getStoredObject(transaction, path);
            // define the newly created resource as null-resource
            nullSo.setNullResource(true);

            // Thats the locking itself
            executeLock(transaction, req, resp);

        } catch (LockFailedException e) {
            sendLockFailError(req, resp);
        } catch (WebdavException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error("Webdav exception", e);
        } catch (ServletException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error("Servlet exception", e);
        }
    }

    private void doRefreshLock(
        Transaction transaction, HttpServletRequest req, HttpServletResponse resp
    ) throws IOException {

        String[] lockTokens = getLockIdFromIfHeader(req);
        String lockToken = null;
        if (lockTokens != null) {
            lockToken = lockTokens[0];
        }

        if (lockToken != null) {
            // Getting LockObject of specified lockToken in If header
            LockedObject refreshLo = resourcelocks.getLockedObjectByID(transaction, lockToken);
            if (refreshLo != null) {
                int timeout = getTimeout(req);

                refreshLo.refreshTimeout(timeout);
                // sending success response
                generateXMLReport(resp, refreshLo);
            } else {
                // no LockObject to given lockToken
                resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            }

        } else {
            resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
        }
    }

    // ------------------------------------------------- helper methods

    /**
     * Executes the LOCK
     */
    private void executeLock(
        Transaction transaction, HttpServletRequest req, HttpServletResponse resp
    ) throws IOException, ServletException {

        // Mac OS lock request workaround
        if (macLockRequest) {
            log.trace("DoLock.execute() : do workaround for user agent '{}'", userAgent);

            doMacLockRequestWorkaround(transaction, req, resp);
        } else {
            // Getting LockInformation from request
            if (getLockInformation(req, resp)) {
                int depth = getDepth(req);
                int lockDuration = getTimeout(req);

                boolean lockSuccess;
                if (exclusive) {
                    lockSuccess = resourcelocks.exclusiveLock(transaction,
                        path,
                        lockOwner,
                        depth,
                        lockDuration
                    );
                } else {
                    lockSuccess = resourcelocks.sharedLock(transaction,
                        path,
                        lockOwner,
                        depth,
                        lockDuration
                    );
                }

                if (lockSuccess) {
                    // Locks successfully placed - return information about
                    LockedObject lo = resourcelocks.getLockedObjectByPath(transaction, path);
                    if (lo != null) {
                        generateXMLReport(resp, lo);
                    } else {
                        resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    }
                } else {
                    sendLockFailError(req, resp);

                    throw new LockFailedException();
                }
            } else {
                // information for LOCK could not be read successfully
                resp.setContentType("text/xml; charset=UTF-8");
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            }
        }
    }

    /**
     * Tries to get the LockInformation from LOCK request
     */
    private boolean getLockInformation(ServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {

        DocumentBuilder documentBuilder = getDocumentBuilder();
        try {
            Document document = documentBuilder.parse(new InputSource(req.getInputStream()));

            // Get the root element of the document

            Node lockInfoNode = document.getDocumentElement();

            if (lockInfoNode != null) {
                NodeList childList = lockInfoNode.getChildNodes();
                Node lockScopeNode = null;
                Node lockTypeNode = null;
                Node lockOwnerNode = null;
                Node currentNode;

                for (int i = 0; i < childList.getLength(); i++) {
                    currentNode = childList.item(i);

                    if (currentNode.getNodeType() == Node.ELEMENT_NODE
                        || currentNode.getNodeType() == Node.TEXT_NODE) {

                        String nodeName = currentNode.getNodeName();

                        if (nodeName.endsWith("locktype")) {
                            lockTypeNode = currentNode;
                        }
                        if (nodeName.endsWith("lockscope")) {
                            lockScopeNode = currentNode;
                        }
                        if (nodeName.endsWith("owner")) {
                            lockOwnerNode = currentNode;
                        }
                    } else {
                        return false;
                    }
                }

                if (lockScopeNode != null) {
                    childList = lockScopeNode.getChildNodes();
                    String scope = null;
                    for (int i = 0; i < childList.getLength(); i++) {
                        currentNode = childList.item(i);

                        if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
                            scope = currentNode.getNodeName();

                            if (scope.endsWith("exclusive")) {
                                exclusive = true;
                            } else if ("shared".equals(scope)) {
                                exclusive = false;
                            }
                        }
                    }
                    if (scope == null) {
                        return false;
                    }

                } else {
                    return false;
                }

                if (lockTypeNode != null) {
                    childList = lockTypeNode.getChildNodes();
                    for (int i = 0; i < childList.getLength(); i++) {
                        currentNode = childList.item(i);

                        if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
                            type = currentNode.getNodeName();

                            if (type.endsWith("write")) {
                                type = "write";
                            } else if ("read".equals(type)) {
                                type = "read";
                            }
                        }
                    }
                    if (type == null) {
                        return false;
                    }
                } else {
                    return false;
                }

                if (lockOwnerNode != null) {
                    childList = lockOwnerNode.getChildNodes();
                    for (int i = 0; i < childList.getLength(); i++) {
                        currentNode = childList.item(i);

                        if (currentNode.getNodeType() == Node.ELEMENT_NODE
                            || currentNode.getNodeType() == Node.TEXT_NODE) {
                            lockOwner = currentNode.getFirstChild().getNodeValue();
                        }
                    }
                }
                if (lockOwner == null) {
                    return false;
                }
            } else {
                return false;
            }

        } catch (DOMException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error("DOM exception", e);
            return false;
        } catch (SAXException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            log.error("SAX exception", e);
            return false;
        }

        return true;
    }

    /**
     * Ties to read the timeout from request
     */
    private static int getTimeout(HttpServletRequest req) {

        String lockDurationStr = req.getHeader("Timeout");

        if (lockDurationStr == null) {
            return DEFAULT_TIMEOUT;
        }
        int commaPos = lockDurationStr.indexOf(',');
        // if multiple timeouts, just use the first one
        if (commaPos != -1) {
            lockDurationStr = lockDurationStr.substring(0, commaPos);
        }
        int lockDuration = DEFAULT_TIMEOUT;
        if (lockDurationStr.startsWith("Second-")) {
            lockDuration = Integer.parseInt(lockDurationStr.substring(7));
        } else {
            if ("infinity".equalsIgnoreCase(lockDurationStr)) {
                lockDuration = MAX_TIMEOUT;
            } else {
                try {
                    lockDuration = Integer.parseInt(lockDurationStr);
                } catch (NumberFormatException e) {
                    lockDuration = MAX_TIMEOUT;
                }
            }
        }
        if (lockDuration <= 0) {
            lockDuration = DEFAULT_TIMEOUT;
        }
        return Math.min(lockDuration, MAX_TIMEOUT);
    }

    /**
     * Generates the response XML with all lock information
     */
    private void generateXMLReport(HttpServletResponse resp, LockedObject lo) throws IOException {

        HashMap<String, String> namespaces = new HashMap<>();
        namespaces.put("DAV:", "D");

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/xml; charset=UTF-8");

        XMLWriter generatedXML = new XMLWriter(resp.getWriter(), namespaces);
        generatedXML.writeXMLHeader();
        generatedXML.writeElement("DAV::prop", XMLWriter.OPENING);
        generatedXML.writeElement("DAV::lockdiscovery", XMLWriter.OPENING);
        generatedXML.writeElement("DAV::activelock", XMLWriter.OPENING);

        generatedXML.writeElement("DAV::locktype", XMLWriter.OPENING);
        generatedXML.writeProperty("DAV::" + type);
        generatedXML.writeElement("DAV::locktype", XMLWriter.CLOSING);

        generatedXML.writeElement("DAV::lockscope", XMLWriter.OPENING);
        if (exclusive) {
            generatedXML.writeProperty("DAV::exclusive");
        } else {
            generatedXML.writeProperty("DAV::shared");
        }
        generatedXML.writeElement("DAV::lockscope", XMLWriter.CLOSING);

        int depth = lo.getLockDepth();

        generatedXML.writeElement("DAV::depth", XMLWriter.OPENING);
        if (depth == INFINITY) {
            generatedXML.writeText("Infinity");
        } else {
            generatedXML.writeText(String.valueOf(depth));
        }
        generatedXML.writeElement("DAV::depth", XMLWriter.CLOSING);

        generatedXML.writeElement("DAV::owner", XMLWriter.OPENING);
        generatedXML.writeElement("DAV::href", XMLWriter.OPENING);
        generatedXML.writeText(lockOwner);
        generatedXML.writeElement("DAV::href", XMLWriter.CLOSING);
        generatedXML.writeElement("DAV::owner", XMLWriter.CLOSING);

        long timeout = lo.getTimeoutMillis();
        generatedXML.writeElement("DAV::timeout", XMLWriter.OPENING);
        generatedXML.writeText("Second-" + timeout / 1000);
        generatedXML.writeElement("DAV::timeout", XMLWriter.CLOSING);

        String lockToken = lo.getID();
        generatedXML.writeElement("DAV::locktoken", XMLWriter.OPENING);
        generatedXML.writeElement("DAV::href", XMLWriter.OPENING);
        generatedXML.writeText("opaquelocktoken:" + lockToken);
        generatedXML.writeElement("DAV::href", XMLWriter.CLOSING);
        generatedXML.writeElement("DAV::locktoken", XMLWriter.CLOSING);

        generatedXML.writeElement("DAV::activelock", XMLWriter.CLOSING);
        generatedXML.writeElement("DAV::lockdiscovery", XMLWriter.CLOSING);
        generatedXML.writeElement("DAV::prop", XMLWriter.CLOSING);

        resp.addHeader("Lock-Token", "<opaquelocktoken:" + lockToken + '>');

        generatedXML.sendData();

    }

    /**
     * Executes the lock for a Mac OS Finder client
     */
    private void doMacLockRequestWorkaround(
        Transaction transaction, HttpServletRequest req, HttpServletResponse resp
    ) throws IOException {
        int depth = getDepth(req);
        int lockDuration = getTimeout(req);
        if (lockDuration < 0 || lockDuration > MAX_TIMEOUT) {
            lockDuration = DEFAULT_TIMEOUT;
        }

        boolean lockSuccess = resourcelocks.exclusiveLock(transaction,
            path,
            lockOwner,
            depth,
            lockDuration
        );

        if (lockSuccess) {
            // Locks successfully placed - return information about
            LockedObject lo = resourcelocks.getLockedObjectByPath(transaction, path);
            if (lo != null) {
                generateXMLReport(resp, lo);
            } else {
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        } else {
            // Locking was not successful
            sendLockFailError(req, resp);
        }
    }

    /**
     * Sends an error report to the client
     */
    private void sendLockFailError(HttpServletRequest req, HttpServletResponse resp)
        throws IOException {
        Map<String, Integer> errorList = new HashMap<>();
        errorList.put(path, WebdavStatus.SC_LOCKED);
        sendReport(req, resp, errorList);
    }

}
