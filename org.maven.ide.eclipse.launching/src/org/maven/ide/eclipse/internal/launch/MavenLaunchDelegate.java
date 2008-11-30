/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.internal.launch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.ui.RefreshTab;
import org.eclipse.jdt.launching.IVMRunner;
import org.eclipse.jdt.launching.JavaLaunchDelegate;
import org.eclipse.jdt.launching.VMRunnerConfiguration;
import org.eclipse.ui.externaltools.internal.model.ExternalToolBuilder;
import org.eclipse.ui.externaltools.internal.model.IExternalToolConstants;
import org.maven.ide.eclipse.MavenPlugin;
import org.maven.ide.eclipse.core.IMavenConstants;
import org.maven.ide.eclipse.core.MavenConsole;
import org.maven.ide.eclipse.core.MavenLogger;
import org.maven.ide.eclipse.embedder.IMavenLauncherConfiguration;
import org.maven.ide.eclipse.embedder.MavenRuntime;
import org.maven.ide.eclipse.embedder.MavenRuntimeManager;
import org.maven.ide.eclipse.util.Util;


@SuppressWarnings("restriction")
public class MavenLaunchDelegate extends JavaLaunchDelegate implements MavenLaunchConstants {

  private static final String LAUNCHER_TYPE = "org.codehaus.classworlds.Launcher";
  
  private MavenRuntime runtime;
  private MavenLauncherConfigurationHandler m2conf;
  private File confFile;

  public void launch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
      throws CoreException {
    MavenConsole console = MavenPlugin.getDefault().getConsole();
    console.logMessage("" + getWorkingDirectory(configuration));
    console.logMessage(" mvn" + getProgramArguments(configuration));

    runtime = MavenLaunchUtils.getMavenRuntime(configuration);

    m2conf = new MavenLauncherConfigurationHandler();
    if (shouldResolveWorkspaceArtifacts(configuration)) {
      m2conf.addArchiveEntry(MavenLaunchUtils.getCliResolver());
    }
    MavenLaunchUtils.addUserComponents(configuration, m2conf);
    runtime.createLauncherConfiguration(m2conf, new NullProgressMonitor());

    File state = MavenPlugin.getDefault().getStateLocation().toFile();
    try {
      File dir = new File(state, "launches");
      dir.mkdirs();
      confFile = File.createTempFile("m2conf", ".tmp", dir);
      confFile.deleteOnExit(); // TODO delete when execution stops
      OutputStream os = new FileOutputStream(confFile);
      try {
        m2conf.save(os);
      } finally {
        os.close();
      }
    } catch (IOException e) {
      throw new CoreException(new Status(IStatus.ERROR, IMavenConstants.PLUGIN_ID, -1, "Can't create m2.conf ", e));
    }
    
    super.launch(configuration, mode, launch, monitor);
  }

  public IVMRunner getVMRunner(final ILaunchConfiguration configuration, String mode) throws CoreException {
    final IVMRunner runner = super.getVMRunner(configuration, mode);
    
    return new IVMRunner() {
      public void run(VMRunnerConfiguration runnerConfiguration, ILaunch launch, IProgressMonitor monitor)
          throws CoreException {
        runner.run(runnerConfiguration, launch, monitor);
        
        IProcess[] processes = launch.getProcesses();
        if(processes!=null && processes.length>0) {
          BackgroundResourceRefresher refresher = new BackgroundResourceRefresher(configuration, processes[0]);
          refresher.startBackgroundRefresh();
        }
      }
    };
  }

  public String getMainTypeName(ILaunchConfiguration configuration) throws CoreException {
    return LAUNCHER_TYPE;
  }

  public String[] getClasspath(ILaunchConfiguration configuration) throws CoreException {
    List<String> cp = m2conf.getRealmEntries(IMavenLauncherConfiguration.LAUNCHER_REALM);
    return cp.toArray(new String[cp.size()]);
  }

  public String getProgramArguments(ILaunchConfiguration configuration) throws CoreException {
    return getProperties(configuration) + //
        getPreferences(configuration) + " " + //
        getGoals(configuration);
  }

  public String getVMArguments(ILaunchConfiguration configuration) throws CoreException {
    /*
    * <pre>
    * %MAVEN_JAVA_EXE% %MAVEN_OPTS% 
    *   -classpath %CLASSWORLDS_JAR% 
    *   "-Dclassworlds.conf=%M2_HOME%\bin\m2.conf" 
    *   "-Dmaven.home=%M2_HOME%" 
    *   org.codehaus.classworlds.Launcher 
    *   %MAVEN_CMD_LINE_ARGS%
    * </pre>
    */

    StringBuffer sb = new StringBuffer();

    // workspace artifact resolution
    if (shouldResolveWorkspaceArtifacts(configuration)) {
      File state = MavenPlugin.getDefault().getMavenProjectManager().getWorkspaceStateFile();
      sb.append("-Dm2eclipse.workspace.state=").append(quote(state.getAbsolutePath()));
    }

    // maven.home
    String location = runtime.getLocation();
    if (location != null) {
      sb.append(" -Dmaven.home=").append(quote(location));
    }

    // m2.conf
    sb.append(" -Dclassworlds.conf=").append(quote(confFile.getAbsolutePath()));

    // user configured entries
    sb.append(" ").append(super.getVMArguments(configuration));

    return sb.toString();
  }

  private String quote(String string) {
    return string.indexOf(' ')>-1 ? "\"" + string + "\"" : string;
  }

  private boolean shouldResolveWorkspaceArtifacts(ILaunchConfiguration configuration) throws CoreException {
    return configuration.getAttribute(ATTR_WORKSPACE_RESOLUTION, false);
  }

  private String getGoals(ILaunchConfiguration configuration) throws CoreException {
    String buildType = ExternalToolBuilder.getBuildType();
    String key = MavenLaunchConstants.ATTR_GOALS;
    if(IExternalToolConstants.BUILD_TYPE_AUTO.equals(buildType)) {
      key = MavenLaunchConstants.ATTR_GOALS_AUTO_BUILD;
    } else if(IExternalToolConstants.BUILD_TYPE_CLEAN.equals(buildType)) {
      key = MavenLaunchConstants.ATTR_GOALS_CLEAN;
    } else if(IExternalToolConstants.BUILD_TYPE_FULL.equals(buildType)) {
      key = MavenLaunchConstants.ATTR_GOALS_AFTER_CLEAN;
    } else if(IExternalToolConstants.BUILD_TYPE_INCREMENTAL.equals(buildType)) {
      key = MavenLaunchConstants.ATTR_GOALS_MANUAL_BUILD;
    }
    String goals = configuration.getAttribute(key, "");
    if(goals==null || goals.length()==0) {
      // use default goals when "full build" returns nothing
      goals = configuration.getAttribute(MavenLaunchConstants.ATTR_GOALS, "");
    }
    
    MavenPlugin.getDefault().getConsole().logMessage("Build type " + buildType + " : " + goals);
    return goals;
  }

  public boolean buildForLaunch(ILaunchConfiguration configuration, String mode, IProgressMonitor monitor) {
    return false;
  }
  
  /**
   * Construct string with properties to pass to JVM as system properties
   */
  private String getProperties(ILaunchConfiguration configuration) {
    StringBuffer sb = new StringBuffer();

    try {
      @SuppressWarnings("unchecked")
      List<String> properties = configuration.getAttribute(ATTR_PROPERTIES, Collections.EMPTY_LIST);
      for(String property : properties) {
        String[] s = property.split("=");
        String n = s[0];
        String v = Util.substituteVar(s[1]);
        if(v.indexOf(' ') >= 0) {
          v = '"' + v + '"';
        }
        sb.append(" -D").append(n).append("=").append(v);
      }
    } catch(CoreException e) {
      String msg = "Exception while getting configuration attribute " + ATTR_PROPERTIES;
      MavenLogger.log(msg, e);
    }

    try {
      String profiles = configuration.getAttribute(ATTR_PROFILES, (String) null);
      if(profiles != null && profiles.trim().length() > 0) {
        sb.append(" -P").append(profiles.replaceAll("\\s+", ","));
      }
    } catch(CoreException ex) {
      String msg = "Exception while getting configuration attribute " + ATTR_PROFILES;
      MavenLogger.log(msg, ex);
    }

    return sb.toString();
  }

  /**
   * Construct string with preferences to pass to JVM as system properties
   */
  private String getPreferences(ILaunchConfiguration configuration) throws CoreException {
    MavenRuntimeManager runtimeManager = MavenPlugin.getDefault().getMavenRuntimeManager();
    
    StringBuffer sb = new StringBuffer();

    sb.append(" -B");

    if(configuration.getAttribute(MavenLaunchConstants.ATTR_DEBUG_OUTPUT, runtimeManager.isDebugOutput())) {
      sb.append(" -X").append(" -e");
    }
    // sb.append(" -D").append(MavenPreferenceConstants.P_DEBUG_OUTPUT).append("=").append(debugOutput);

    if(configuration.getAttribute(MavenLaunchConstants.ATTR_OFFLINE, runtimeManager.isOffline())) {
      sb.append(" -o");
    }
    // sb.append(" -D").append(MavenPreferenceConstants.P_OFFLINE).append("=").append(offline);

    if(configuration.getAttribute(MavenLaunchConstants.ATTR_UPDATE_SNAPSHOTS, false)) {
      sb.append(" -U");
    }
    
    if(configuration.getAttribute(MavenLaunchConstants.ATTR_NON_RECURSIVE, false)) {
      sb.append(" -N");
    }
    
    if(configuration.getAttribute(MavenLaunchConstants.ATTR_SKIP_TESTS, false)) {
      sb.append(" -Dmaven.test.skip=true");
    }
    
    String settings = runtimeManager.getUserSettingsFile();
//    if(settings==null) {
//      settings = getMavenRuntime(configuration).getSettings();
//    }
    if(settings != null && settings.trim().length() > 0) {
      sb.append(" -s ");
      if(settings.indexOf(' ') > -1) {
        sb.append('\"').append(settings).append('\"');
      } else {
        sb.append(settings);
      }
    }

    // boolean b = preferenceStore.getBoolean(MavenPreferenceConstants.P_CHECK_LATEST_PLUGIN_VERSION);
    // sb.append(" -D").append(MavenPreferenceConstants.P_CHECK_LATEST_PLUGIN_VERSION).append("=").append(b);

    // b = preferenceStore.getBoolean(MavenPreferenceConstants.P_UPDATE_SNAPSHOTS);
    // sb.append(" -D").append(MavenPreferenceConstants.P_UPDATE_SNAPSHOTS).append("=").append(b);

    // String s = preferenceStore.getString(MavenPreferenceConstants.P_GLOBAL_CHECKSUM_POLICY);
    // if(s != null && s.trim().length() > 0) {
    //   sb.append(" -D").append(MavenPreferenceConstants.P_GLOBAL_CHECKSUM_POLICY).append("=").append(s);
    // }
    
    return sb.toString();
  }

  /**
   * Refreshes resources as specified by a launch configuration, when 
   * an associated process terminates.
   * 
   * Adapted from org.eclipse.ui.externaltools.internal.program.launchConfigurations.BackgroundResourceRefresher
   */
  public static class BackgroundResourceRefresher implements IDebugEventSetListener  {
    final ILaunchConfiguration configuration;
    final IProcess process;
    
    public BackgroundResourceRefresher(ILaunchConfiguration configuration, IProcess process) {
      this.configuration = configuration;
      this.process = process;
    }
    
    /**
     * If the process has already terminated, resource refreshing is done
     * immediately in the current thread. Otherwise, refreshing is done when the
     * process terminates.
     */
    public void startBackgroundRefresh() {
      synchronized (process) {
        if (process.isTerminated()) {
          refresh();
        } else {
          DebugPlugin.getDefault().addDebugEventListener(this);
        }
      }
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.debug.core.IDebugEventSetListener#handleDebugEvents(org.eclipse.debug.core.DebugEvent[])
     */
    public void handleDebugEvents(DebugEvent[] events) {
      for (int i = 0; i < events.length; i++) {
        DebugEvent event = events[i];
        if (event.getSource() == process && event.getKind() == DebugEvent.TERMINATE) {
          DebugPlugin.getDefault().removeDebugEventListener(this);
          refresh();
          break;
        }
      }
    }
    
    /**
     * Submits a job to do the refresh
     */
    protected void refresh() {
      Job job= new Job("Refreshing resources...") {
        public IStatus run(IProgressMonitor monitor) {
          try {
            RefreshTab.refreshResources(configuration, monitor);
            return Status.OK_STATUS;
          } catch (CoreException e) {
            MavenLogger.log(e);
            return e.getStatus();
          } 
        }
      };
      job.schedule();
    }
  }

}
