/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.v3.admin;

import com.sun.enterprise.admin.util.ClusterOperationUtil;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.v3.common.PropsFileActionReporter;
import org.glassfish.api.ActionReport;
import org.glassfish.api.ActionReport.ExitCode;
import org.glassfish.api.Param;
import org.glassfish.api.admin.*;
import org.glassfish.external.statistics.Statistic;
import org.glassfish.external.statistics.Stats;
import org.glassfish.external.statistics.impl.StatisticImpl;
import org.glassfish.flashlight.MonitoringRuntimeDataRegistry;
import org.glassfish.internal.api.Target;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PerLookup;
import org.jvnet.hk2.config.Dom;
import org.jvnet.hk2.config.types.Property;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.util.*;

import static com.sun.enterprise.util.SystemPropertyConstants.SLASH;

/**
 * User: Jerome Dochez
 * Date: Jul 10, 2008
 * Time: 12:17:26 AM
 */
@Service(name = "get")
@Scoped(PerLookup.class)
@CommandLock(CommandLock.LockType.NONE)
@ExecuteOn({RuntimeType.DAS, RuntimeType.INSTANCE})
public class GetCommand extends V2DottedNameSupport implements AdminCommand {

    @Inject
    private MonitoringReporter mr;
    
    @Inject
    Domain domain;

    @Inject
    ServerEnvironment serverEnv;

    @Inject
    Target targetService;

    @Inject
    Habitat habitat;

    @Param(optional = true, defaultValue = "false", shortName = "m")
    Boolean monitor;

    @Param(primary = true)
    String pattern;

    @Inject(optional = true)
    private MonitoringRuntimeDataRegistry mrdr;

    private final String DOTTED_NAME = ".dotted-name";
    final private static LocalStringManagerImpl localStrings =
            new LocalStringManagerImpl(GetCommand.class);

    public void execute(AdminCommandContext context) {

        ActionReport report = context.getActionReport();

        /* Issue 5918 Used in ManifestManager to keep output sorted */
        try {
            PropsFileActionReporter reporter = (PropsFileActionReporter) report;
            reporter.useMainChildrenAttribute(true);
        } catch (ClassCastException e) {
            // ignore this is not a manifest output.
        }

        if (monitor) {
            getMonitorAttributes(report, context);
            String old = report.getMessage();
            String append = "\nXXXXXXXXX\n" + mr.toString();
            report.setMessage(old == null ? append : old + append);
            return;
        }

        // check for logging patterns
        if (pattern.contains(".log-service")) {
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(localStrings.getLocalString("admin.get.invalid.logservice.command", "For getting log levels/attributes use list-log-levels/list-log-attributes command."));
            return;
        }

        //check for incomplete dotted name
        if (!pattern.equals("*")) {
            if (pattern.lastIndexOf(".") == -1 || pattern.lastIndexOf(".") == (pattern.length() - 1)) {
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                //report.setMessage("Missing expected dotted name part");
                report.setMessage(localStrings.getLocalString("missing.dotted.name", "Missing expected dotted name part"));
                return;
            }
        }

        // first let's get the parent for this pattern.
        TreeNode[] parentNodes = getAliasedParent(domain, pattern);
        Map<Dom, String> dottedNames = new HashMap<Dom, String>();
        for (TreeNode parentNode : parentNodes) {
            dottedNames.putAll(getAllDottedNodes(parentNode.node));
        }

        // reset the pattern.
        String prefix = "";
        if (!pattern.startsWith(parentNodes[0].relativeName)) {
            prefix = pattern.substring(0, pattern.indexOf(parentNodes[0].relativeName));
        }
        pattern = parentNodes[0].relativeName;

        Map<Dom, String> matchingNodes = getMatchingNodes(dottedNames, pattern);
        if (matchingNodes.isEmpty() && pattern.lastIndexOf('.') != -1) {
            // it's possible the user is just looking for an attribute, let's remove the
            // last element from the pattern.
            matchingNodes = getMatchingNodes(dottedNames, pattern.substring(0, pattern.lastIndexOf(".")));
        }

        //No matches found - report the failure and return
        if (matchingNodes.isEmpty()) {
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            //report.setMessage("Dotted name path \"" + prefix + pattern + "\" not found.");
            report.setMessage(localStrings.getLocalString("admin.get.path.notfound", "Dotted name path {0} not found.", prefix + pattern));
            return;
        }

        List<Map.Entry> matchingNodesSorted = sortNodesByDottedName(matchingNodes);
        matchingNodesSorted = applyOverrideRules(matchingNodesSorted);
        boolean foundMatch = false;
        for (Map.Entry<Dom, String> node : matchingNodesSorted) {
            // if we get more of these special cases, we should switch to a Renderer pattern
            if (Property.class.getName().equals(node.getKey().model.targetTypeName)) {
                // special display for properties...
                if (matches(node.getValue(), pattern)) {
                    ActionReport.MessagePart part = report.getTopMessagePart().addChild();
                    part.setChildrenType("DottedName");
                    part.setMessage(prefix + node.getValue() + "=" + encode(node.getKey().attribute("value")));
                    foundMatch = true;
                }
            } else {
                Map<String, String> attributes = getNodeAttributes(node.getKey(), pattern);
                TreeMap<String, String> attributesSorted = new TreeMap(attributes);
                for (Map.Entry<String, String> name : attributesSorted.entrySet()) {
                    String finalDottedName = node.getValue() + "." + name.getKey();
                    if (matches(finalDottedName, pattern)) {
                        ActionReport.MessagePart part = report.getTopMessagePart().addChild();
                        part.setChildrenType("DottedName");
                        part.setMessage(prefix + node.getValue() + "." + name.getKey() + "=" + name.getValue());
                        foundMatch = true;
                    }
                }
            }
        }
        if (!foundMatch) {
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            //report.setMessage("No object found matching " + pattern);
            report.setMessage(localStrings.getLocalString("admin.get.path.notfound", "Dotted name path {0} not found.", prefix + pattern));

        }
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private void getMonitorAttributes(ActionReport report, AdminCommandContext ctxt) {
        mr.prepare(ctxt, pattern);
        mr.execute();
    }
}
