/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.flink.client.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.Iterator;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.pattern.Patterns;
import akka.util.Timeout;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.akka.AkkaUtils;
import org.apache.flink.runtime.executiongraph.ExecutionGraph;
import org.apache.flink.runtime.jobmanager.JobManager;
import org.apache.flink.runtime.messages.JobManagerMessages;
import org.apache.flink.runtime.messages.JobManagerMessages.RunningJobs;
import scala.concurrent.Await;
import scala.concurrent.duration.FiniteDuration;
import scala.concurrent.Future;


public class JobsInfoServlet extends HttpServlet {
	/**
	 * Serial UID for serialization interoperability.
	 */
	private static final long serialVersionUID = 558077298726449201L;

	// ------------------------------------------------------------------------

	private final Configuration config;

	private final ActorSystem system;

	private final FiniteDuration timeout;

	private final ActorRef jobmanager;
	
	public JobsInfoServlet(Configuration flinkConfig) {
		this.config = flinkConfig;
		system = ActorSystem.create("JobsInfoServletActorSystem",
				AkkaUtils.getDefaultAkkaConfig());
		this.timeout = AkkaUtils.getTimeout(flinkConfig);

		String jmHost = config.getString(ConfigConstants.JOB_MANAGER_IPC_ADDRESS_KEY, null);
		int jmPort = config.getInteger(ConfigConstants.JOB_MANAGER_IPC_PORT_KEY,
				ConfigConstants.DEFAULT_JOB_MANAGER_IPC_PORT);

		InetSocketAddress address = new InetSocketAddress(jmHost, jmPort);

		Future<ActorRef> jobManagerFuture = JobManager.getJobManagerRemoteReferenceFuture(address, system, timeout);

		try {
			this.jobmanager = Await.result(jobManagerFuture, timeout);
		} catch (Exception ex) {
			throw new RuntimeException("Could not find job manager at specified address " +
					JobManager.getRemoteJobManagerAkkaURL(address) + ".");
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		//resp.setContentType("application/json");
		
		try {
			final Future<Object> response = Patterns.ask(jobmanager,
					JobManagerMessages.getRequestRunningJobs(),
					new Timeout(timeout));

			Object result = null;

			try {
				result = Await.result(response, timeout);
			} catch (Exception exception) {
				throw new IOException("Could not retrieve the running jobs from the job manager.",
						exception);
			}

			if(!(result instanceof RunningJobs)) {
				throw new RuntimeException("ReqeustRunningJobs requires a response of type " +
						"RunningJob. Instead the response is of type " + result.getClass() + ".");
			} else {

				final Iterator<ExecutionGraph> graphs = ((RunningJobs) result).
						asJavaIterable().iterator();

				resp.setStatus(HttpServletResponse.SC_OK);
				PrintWriter wrt = resp.getWriter();
				wrt.write("[");
				while(graphs.hasNext()){
					ExecutionGraph graph = graphs.next();
					//Serialize job to json
					wrt.write("{");
					wrt.write("\"jobid\": \"" + graph.getJobID() + "\",");
					if(graph.getJobName() != null) {
						wrt.write("\"jobname\": \"" + graph.getJobName()+"\",");
					}
					wrt.write("\"status\": \""+ graph.getState() + "\",");
					wrt.write("\"time\": " + graph.getStatusTimestamp(graph.getState()));
					wrt.write("}");
					//Write seperator between json objects
					if(graphs.hasNext()) {
						wrt.write(",");
					}
				}
				wrt.write("]");
			}
		} catch (Throwable t) {
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			resp.getWriter().print(t.getMessage());
		}
	}

	protected String escapeString(String str) {
		int len = str.length();
		char[] s = str.toCharArray();
		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < len; i += 1) {
			char c = s[i];
			if ((c == '\\') || (c == '"') || (c == '/')) {
				sb.append('\\');
				sb.append(c);
			} else if (c == '\b') {
				sb.append("\\b");
			} else if (c == '\t') {
				sb.append("\\t");
			} else if (c == '\n') {
				sb.append("<br>");
			} else if (c == '\f') {
				sb.append("\\f");
			} else if (c == '\r') {
				sb.append("\\r");
			} else {
				if (c < ' ') {
					// Unreadable throw away
				} else {
					sb.append(c);
				}
			}
		}

		return sb.toString();
	}
}
