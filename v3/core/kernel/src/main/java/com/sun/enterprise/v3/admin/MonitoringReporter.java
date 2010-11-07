/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
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
import com.sun.enterprise.config.serverbeans.*;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.util.StringUtils;
import java.lang.reflect.Proxy;
import java.util.*;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.*;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.external.statistics.Statistic;
import org.glassfish.external.statistics.Stats;
import org.glassfish.external.statistics.impl.StatisticImpl;
import org.glassfish.internal.api.*;
import org.jvnet.hk2.component.*;
import static org.glassfish.api.ActionReport.ExitCode.FAILURE;
import static org.glassfish.api.ActionReport.ExitCode.SUCCESS;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.flashlight.MonitoringRuntimeDataRegistry;
import org.jvnet.hk2.annotations.*;
import org.jvnet.hk2.component.PerLookup;
import static com.sun.enterprise.util.SystemPropertyConstants.SLASH;

/**
 *
 * @author Byron Nevins
 * First breathed life on November 6, 2010
 *
 * Note: what do you suppose is the worst possible name for a TreeNode class?
 * Correct!  TreeNode!  Clashing names is why we have to explicitly use this ghastly
 * name:  org.glassfish.flashlight.datatree.TreeNode all over the place...
 */
@Service (name = "MonitoringReporter")
@Scoped(PerLookup.class)
@ExecuteOn({RuntimeType.DAS, RuntimeType.INSTANCE})
public class MonitoringReporter extends V2DottedNameSupport {
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("\nPattern=[").append(pattern).append("]").append('\n');

        if (!targets.isEmpty()) {
            for (Server server : targets)
                if (server != null)
                    sb.append("Server=[").append(server.getName()).append("]").append('\n');
        }
        else
            sb.append("No Targets");

        return sb.toString();
    }
    ///////////////////////////////////////////////////////////////////////
    ////////////////////////  The API Methods  ///////////////////////////
    ///////////////////////////////////////////////////////////////////////

    public void prepare(AdminCommandContext c, String arg) {
        context = c;
        report = context.getActionReport();

        // DAS runs the show on this command.  If we are running in an
        // instance -- that means we should call runLocally() AND it also
        // means that the pattern is already perfect!

        if (isDas())
            prepareDas(arg);
        else
            prepareInstance(arg);
    }

    public void execute() {
        // TODO remove?  make it an exception???
        if (hasError())
            return;

        runLocally();
        runRemotely();
    }

    ///////////////////////////////////////////////////////////////////////
    ////////////////////////  ALL PRIVATE BELOW ///////////////////////////
    ///////////////////////////////////////////////////////////////////////

    private void prepareDas(String arg) {
        // TODO throw an exception if any errors????
        try {
            setSuccess();
            userarg = arg;

            if (!validate())
                return;
        }
        catch (Exception e) {
            setError(Strings.get("admin.get.monitoring.unknown", e.getMessage()));
            report.setFailureCause(e);
        }
    }

    private void prepareInstance(String arg) {
        // TODO throw an exception if any errors!
        pattern = arg;
    }
    // mostly just copied over from old "get" implementation
    private void runLocally() {

        // don't run if this is DAS **and** DAS is not in the server list.
        // otherwise we are in an instance and definitely want to run!
         if (isDas() && !dasIsInList())
            return;

       // say the pattern is "something" -->
         // we want "server.something" for DAS and "i1.server.something" for i1
         // Yes -- this is difficult to get perfect!!!  What if user entered
         //"server.something"?

        String localPattern = prependServerDot(pattern);
        
        // Weird -- but this is how it works internally!
        if(!isDas())
            localPattern = serverEnv.getInstanceName() + "." + localPattern;

        org.glassfish.flashlight.datatree.TreeNode tn = datareg.get(serverEnv.getInstanceName());

        if (tn == null) {
            // No monitoring data, so nothing to list
            // officially this is considered a "success"
            setSuccess(Strings.get("admin.get.monitoring.empty"));
            return;
        }

        TreeMap map = new TreeMap();
        List<org.glassfish.flashlight.datatree.TreeNode> ltn = tn.getNodes(localPattern);
        boolean singleStat = false;

        if (ltn == null || ltn.isEmpty()) {
            org.glassfish.flashlight.datatree.TreeNode parent = tn.getPossibleParentNode(localPattern);

            if (parent != null) {
                ltn = new ArrayList<org.glassfish.flashlight.datatree.TreeNode>(1);
                ltn.add(parent);
                singleStat = true;
            }
        }

        if (!singleStat)
            localPattern = null; // signal to method call below.  localPattern was already used above...

        for (org.glassfish.flashlight.datatree.TreeNode tn1 : sortTreeNodesByCompletePathName(ltn)) {
            if (!tn1.hasChildNodes()) {
                insertNameValuePairs(map, tn1, localPattern);
            }
        }

        Iterator it = map.keySet().iterator();
        Object obj;
        while (it.hasNext()) {
            obj = it.next();
            String s = obj.toString();
            ActionReport.MessagePart part = report.getTopMessagePart().addChild();
            part.setMessage(s.replace(SLASH, "/") + " = " + map.get(obj));
        }
        setSuccess();
    }

    /**
     * This can be a bit confusing.  It is sort of like a recursive call.
     * GetCommand will be called on the instance.  BUT -- the pattern arg will
     * just have the actual pattern -- the target name will NOT be in there!
     * So "runLocally" will be called on the instance.  this method will ONLY
     * run on DAS (guaranteed!)
     */
    private void runRemotely() {
        if (!isDas())
            return;

        List<Server> remoteServers = getRemoteServers();

        if(remoteServers.isEmpty())
            return;

        try {
            ParameterMap paramMap = new ParameterMap();
            paramMap.set("monitor", "true");
            paramMap.set("DEFAULT", pattern);
            ClusterOperationUtil.replicateCommand("get", FailurePolicy.Error, FailurePolicy.Warn, remoteServers,
                    context, paramMap, habitat);
        }
        catch (Exception ex) {
            setError(Strings.get("admin.get.monitoring.remote.error", getNames(remoteServers)));
        }
    }

    private String prependServerDot(String s) {
        if(s.startsWith(SERVERDOT))
            return s;
        else
            return SERVERDOT + s;
    }

    private boolean validate() {
        if (datareg == null) {
            setError(Strings.get("admin.get.no.monitoring"));
            return false;
        }

        if (!initPatternAndTargets())
            return false;

        return true;
    }

    /*
     * VERY VERY complicated to get this right!
     */
    private boolean initPatternAndTargets() {
        Server das = domain.getServerNamed("server");

        // no DAS in here!
        List<Server> allServers = targetService.getAllInstances();

        allServers.add(das);

        // 1.  nothing
        // 2.  *
        // 3.  *.   --> which is a weird input but let's accept it anyway!
        // 4   .   --> very weird but we'll take it
        if (!StringUtils.ok(userarg)
                || userarg.equals("*")
                || userarg.equals(".")
                || userarg.equals("*.")) {
            // By definition this means ALL servers and ALL data
            targets = allServers;
            pattern = "*";
            return true;
        }

        // 5.   *..
        // 6.   *.<something>
        if (userarg.startsWith("*.")) {
            targets = allServers;

            // note: it can NOT be just "*." -- there is something at posn #2 !!
            pattern = userarg.substring(2);

            // "*.." is an error
            if (pattern.startsWith(".")) {
                String specificError = Strings.get("admin.get.monitoring.nodoubledot");
                setError(Strings.get("admin.get.monitoring.invalidpattern", specificError));
                return false;
            }
            return true;
        }

        int index = userarg.indexOf(".");
        String targetName;
        String otherstuff;

        if (index >= 0) {
            targetName = userarg.substring(0, index);

            if (userarg.length() == index + 1) {
                // 7. <servername>.
                pattern = "*";
            }
            else
                // 8. <servername>.<pattern>
                pattern = userarg.substring(index + 1);
        }
        else {
            // no dots in userarg
            // 9. <servername>
            targetName = userarg;
            pattern = "*";
        }

        // note that "server" is hard-coded everywhere in GF code.  We're stuck with it!!

        if (targetName.equals("server") || targetName.equals("server-config")) {
            targets.add(das);
            return true;
        }

        // targetName is either 1 or more instances or garbage!
        targets = targetService.getInstances(targetName);

        if (targets.isEmpty()) {
            setError(Strings.get("admin.get.monitoring.invalidtarget", userarg));
            return false;
        }

        return true;
    }

    private void insertNameValuePairs(
            TreeMap map, org.glassfish.flashlight.datatree.TreeNode tn1, String exactMatch) {
        String name = tn1.getCompletePathName();
        Object value = tn1.getValue();
        if (tn1.getParent() != null) {
            map.put(tn1.getParent().getCompletePathName() + DOTTED_NAME,
                    tn1.getParent().getCompletePathName());
        }
        if (value instanceof Stats) {
            for (Statistic s : ((Stats) value).getStatistics()) {
                String statisticName = s.getName();
                if (statisticName != null) {
                    statisticName = s.getName().toLowerCase();
                }
                addStatisticInfo(s, name + "." + statisticName, map);
            }
        }
        else if (value instanceof Statistic) {
            addStatisticInfo(value, name, map);
        }
        else {
            map.put(name, value);
        }

        // IT 8985 bnevins
        // Hack to get single stats.  The code above above would take a lot of
        // time to unwind.  For development speed we just remove unwanted items
        // after the fact...

        if (exactMatch != null) {
            Object val = map.get(exactMatch);
            map.clear();

            if (val != null)
                map.put(exactMatch, val);
        }
    }

    private void addStatisticInfo(Object value, String name, TreeMap map) {
        Map<String, Object> statsMap;
        // Most likely we will get the proxy of the StatisticImpl,
        // reconvert that so you can access getStatisticAsMap method
        if (Proxy.isProxyClass(value.getClass())) {
            statsMap = ((StatisticImpl) Proxy.getInvocationHandler(value)).getStaticAsMap();
        }
        else {
            statsMap = ((StatisticImpl) value).getStaticAsMap();
        }
        for (String attrName : statsMap.keySet()) {
            Object attrValue = statsMap.get(attrName);
            map.put(name + "-" + attrName, attrValue);
        }
    }

    private void setError(String msg) {
        report.setActionExitCode(FAILURE);
        appendMessage(msg);
        clear();
    }

    private void setSuccess() {
        report.setActionExitCode(SUCCESS);
    }

    private void setSuccess(String msg) {
        setSuccess();
        appendMessage(msg);
    }

    private void appendMessage(String newMessage) {
        String oldMessage = report.getMessage();

        if (oldMessage == null)
            report.setMessage(newMessage);
        else
            report.setMessage(oldMessage + "\n" + newMessage);
    }

    private boolean hasError() {
        //return report.hasFailures();
        return report.getActionExitCode() == FAILURE;
    }

    private void clear() {
        targets = Collections.emptyList();
        pattern = "";
    }

    private List<Server> getRemoteServers() {
        // only call on DAS !!!
        if (!isDas())
            throw new RuntimeException("Internal Error"); // todo?

        List<Server> notdas = new ArrayList<Server>(targets.size());
        String dasName = serverEnv.getInstanceName();

        for (Server server : targets) {
            if (!dasName.equals(server.getName()))
                notdas.add(server);
        }

        return notdas;
    }

    private boolean dasIsInList() {
        return getRemoteServers().size() != targets.size();
    }

    private String getNames(List<Server> list) {
        boolean first = true;
        String ret = "";

        for (Server server : list) {
            if (first)
                first = false;
            else
                ret += ", ";

            ret += server.getName();
        }
        return ret;
    }

    private boolean isDas() {
        return serverEnv.isDas();
    }

    /*
     * Surprise!  The variables are down here.  All the variables are private.
     * That means they are an implementation detail and are hidden at the bottom
     * of the file.
     */
    List<Server> targets = new ArrayList<Server>();
    private ActionReport report;
    private AdminCommandContext context;
    private String pattern;
    private String userarg;
    @Inject(optional = true)
    private MonitoringRuntimeDataRegistry datareg;
    @Inject
    private Domain domain;
    @Inject
    private Target targetService;
    @Inject
    ServerEnvironment serverEnv;
    @Inject
    Habitat habitat;
    private final static String DOTTED_NAME = ".dotted-name";
    private static final String SERVERDOT = "server.";
}
