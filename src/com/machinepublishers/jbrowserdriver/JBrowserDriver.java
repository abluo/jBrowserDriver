/* 
 * jBrowserDriver (TM)
 * Copyright (C) 2014-2016 Machine Publishers, LLC
 * 
 * Sales and support: ops@machinepublishers.com
 * Updates: https://github.com/MachinePublishers/jBrowserDriver
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
package com.machinepublishers.jbrowserdriver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.internal.Killable;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.remote.CommandExecutor;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.ErrorHandler;
import org.openqa.selenium.remote.FileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.SessionId;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.listener.ProcessListener;
import org.zeroturnaround.exec.stream.LogOutputStream;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.Processes;

import com.machinepublishers.jbrowserdriver.diagnostics.Test;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

/**
 * A Selenium-compatible and WebKit-based web driver written in pure Java.
 * <p>
 * See <a href="https://github.com/machinepublishers/jbrowserdriver#usage">
 * https://github.com/machinepublishers/jbrowserdriver#usage</a> for basic usage info.
 * <p>
 * Licensed under the Apache License version 2.0.
 * <p>
 * Sales and support: ops@machinepublishers.com
 */
public class JBrowserDriver extends RemoteWebDriver implements Killable {

  //TODO handle jbd.fork=false

  /**
   * This can be passed to sendKeys to delete all the text in a text field.
   */
  public static final String KEYBOARD_DELETE = Util.KEYBOARD_DELETE;
  private static final Intercept intercept;
  static {
    Intercept interceptTmp = null;
    try {
      interceptTmp = new Intercept();
    } catch (Throwable t) {
      Util.handleException(t);
    }
    intercept = interceptTmp;
  }
  private static final Set<Integer> childPortsAvailable = new LinkedHashSet<Integer>();
  private static final Set<Integer> childPortsUsed = new LinkedHashSet<Integer>();
  private static final List<String> args;
  private static final List<Object> waiting = new ArrayList<Object>();
  private static int curWaiting;
  private static final Set<String> filteredLogs = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(new String[] {
          "Warning: Single GUI Threadiong is enabled, FPS should be slower"
      })));
  private static final AtomicLong sessionIdCounter = new AtomicLong();
  private final Object key = new Object();
  private final JBrowserDriverRemote remote;
  private final Logs logs;
  private final AtomicReference<Process> process = new AtomicReference<Process>();
  private final AtomicInteger configuredChildPort = new AtomicInteger();
  private final AtomicInteger actualChildPort = new AtomicInteger();
  private final AtomicInteger actualParentPort = new AtomicInteger();
  private final AtomicReference<OptionsLocal> options = new AtomicReference<OptionsLocal>();
  private final SessionId sessionId;

  static {
    Policy.init();
  }

  static {
    List<String> argsTmp = new ArrayList<String>();
    try {
      File javaBin = new File(System.getProperty("java.home") + "/bin/java");
      if (!javaBin.exists()) {
        javaBin = new File(javaBin.getCanonicalPath() + ".exe");
      }
      argsTmp.add(javaBin.getCanonicalPath());
      for (Object keyObj : System.getProperties().keySet()) {
        String key = keyObj.toString();
        if (key != null && key.startsWith("jbd.rmi.")) {
          argsTmp.add("-D" + key.substring("jbd.rmi.".length()) + "=" + System.getProperty(key));
        }
      }

      List<File> items = new ClasspathFinder().getUniqueClasspathElements();
      final File classpathDir = Files.createTempDirectory("jbd_classpath_").toFile();
      Runtime.getRuntime().addShutdownHook(new FileRemover(classpathDir));
      List<String> paths = new ArrayList<String>();
      for (File curItem : items) {
        paths.add(curItem.getAbsoluteFile().toURI().toURL().toExternalForm());
        if (curItem.isFile() && curItem.getPath().endsWith(".jar")) {
          try (ZipFile jar = new ZipFile(curItem)) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
              ZipEntry entry = entries.nextElement();
              if (entry.getName().endsWith(".jar")) {
                try (InputStream in = jar.getInputStream(entry)) {
                  File childJar = new File(classpathDir,
                      Util.randomFileName() + ".jar");
                  Files.copy(in, childJar.toPath());
                  paths.add(childJar.getAbsoluteFile().toURI().toURL().toExternalForm());
                  childJar.deleteOnExit();
                }
              }
            }
          }
        }
      }
      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
      manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, StringUtils.join(paths, ' '));
      File classpathJar = new File(classpathDir, "classpath.jar");
      classpathJar.deleteOnExit();
      new JarOutputStream(new FileOutputStream(classpathJar), manifest).close();
      argsTmp.add("-classpath");
      argsTmp.add(classpathJar.getCanonicalPath());
    } catch (Throwable t) {
      Util.handleException(t);
    }
    args = Collections.unmodifiableList(argsTmp);
  }

  /**
   * Run diagnostic tests.
   * 
   * @return Errors or an empty list if no errors found.
   */
  public static List<String> test() {
    return Test.run();
  }

  private final File tmpDir;
  private final FileRemover shutdownHook;

  /**
   * Constructs a browser with default settings, UTC timezone, and no proxy.
   */
  public JBrowserDriver() {
    this(Settings.builder().build());
  }

  /**
   * Use {@link Settings#builder()} ...buildCapabilities() to create settings to pass to this constructor.
   * 
   * This constructor is mostly useful for Selenium Server itself to use.
   * 
   * @param capabilities
   */
  public JBrowserDriver(Capabilities capabilities) {
    this(Settings.builder().build(capabilities));
    if (!(capabilities instanceof Serializable)) {
      capabilities = new DesiredCapabilities(capabilities);
    }
    try {
      remote.storeCapabilities(capabilities);
    } catch (Throwable t) {
      Util.handleException(t);
    }
  }

  /**
   * Use {@link Settings#builder()} ...build() to create settings to pass to this constructor.
   * 
   * @param settings
   */
  public JBrowserDriver(final Settings settings) {
    File tmpDir = null;
    try {
      tmpDir = Files.createTempDirectory("jbd_tmp_").toFile();
    } catch (Throwable t) {
      Util.handleException(t);
    }
    this.tmpDir = tmpDir;
    this.shutdownHook = new FileRemover(tmpDir);
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    synchronized (childPortsAvailable) {
      for (int curPort : settings.childPorts()) {
        if (!childPortsAvailable.contains(curPort) && !childPortsUsed.contains(curPort)) {
          childPortsAvailable.add(curPort);
        }
      }
      waiting.add(key);
      while (true) {
        boolean ready = false;
        curWaiting = curWaiting >= waiting.size() ? 0 : curWaiting;
        if (key.equals(waiting.get(curWaiting)) && !childPortsAvailable.isEmpty()) {
          for (int curPort : settings.childPorts()) {
            if (childPortsAvailable.contains(curPort)) {
              configuredChildPort.set(curPort);
              ready = true;
              break;
            }
          }
          if (ready) {
            curWaiting = 0;
            break;
          } else {
            ++curWaiting;
            childPortsAvailable.notifyAll();
          }
        }
        try {
          childPortsAvailable.wait();
        } catch (InterruptedException e) {}
      }
      waiting.remove(key);
      childPortsAvailable.remove(configuredChildPort.get());
      childPortsUsed.add(configuredChildPort.get());
    }
    launchProcess(settings.host(), settings.parentPort(), configuredChildPort.get());
    JBrowserDriverRemote instanceTmp = null;
    try {
      instanceTmp = (JBrowserDriverRemote) LocateRegistry
          .getRegistry(settings.host(), actualChildPort.get(),
              new SocketFactory(settings.host(), actualParentPort.get(), actualChildPort.get()))
          .lookup("JBrowserDriverRemote");
      instanceTmp.setUp(settings);
    } catch (Throwable t) {
      Util.handleException(t);
    }
    remote = instanceTmp;
    LogsRemote logsRemote = null;
    try {
      logsRemote = remote.logs();
    } catch (Throwable t) {
      Util.handleException(t);
    }
    sessionId = new SessionId(new StringBuilder()
        .append("[Instance ")
        .append(sessionIdCounter.incrementAndGet())
        .append("][Port ")
        .append(actualChildPort.get())
        .append("]")
        .append(actualChildPort.get() == configuredChildPort.get()
            ? "" : "[Process " + Math.abs(configuredChildPort.get()) + "]")
        .toString());
    logs = new Logs(logsRemote);
  }

  @Override
  protected void finalize() throws Throwable {
    try {
      super.finalize();
    } catch (Throwable t) {}
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
      shutdownHook.run();
    } catch (Throwable t) {}
  }

  private void launchProcess(final String host, final int parentPort, final int childPort) {
    final AtomicBoolean ready = new AtomicBoolean();
    final AtomicReference<String> logPrefix = new AtomicReference<String>("");
    new Thread(new Runnable() {
      @Override
      public void run() {
        List<String> myArgs = new ArrayList<String>(args);
        myArgs.add("-Djava.io.tmpdir=" + tmpDir.getAbsolutePath());
        myArgs.add("-Djava.rmi.server.hostname=" + host);
        myArgs.add(JBrowserDriverServer.class.getName());
        myArgs.add(Integer.toString(parentPort));
        myArgs.add(Integer.toString(childPort));
        try {
          new ProcessExecutor()
              .environment(System.getenv())
              .addListener(new ProcessListener() {
                @Override
                public void afterStop(Process process) {
                  intercept.deallocate();
                }

                @Override
                public void beforeStart(ProcessExecutor executor) {
                  intercept.allocate();
                }

                @Override
                public void afterStart(Process proc, ProcessExecutor executor) {
                  process.set(proc);
                }
              })
              .redirectOutput(new LogOutputStream() {
                boolean done = false;

                @Override
                protected void processLine(String line) {
                  if (line != null && !line.isEmpty()) {
                    if (!done) {
                      synchronized (ready) {
                        if (line.startsWith("parent on port ")) {
                          actualParentPort.set(Integer.parseInt(
                              line.substring("parent on port ".length())));
                        } else if (line.startsWith("child on port ")) {
                          actualChildPort.set(Integer.parseInt(
                              line.substring("child on port ".length())));
                          String portId = actualChildPort.get() != childPort
                              ? actualChildPort.get() + "; Process " + Math.abs(childPort) : Integer.toString(childPort);
                          logPrefix.set("[Port " + portId + "] ");
                          ready.set(true);
                          ready.notify();
                          done = true;
                        } else if (!filteredLogs.contains(line)) {
                          System.out.println(logPrefix + line);
                        }
                      }
                    } else if (!filteredLogs.contains(line)) {
                      System.out.println(logPrefix + line);
                    }
                  }
                }
              })
              .redirectError(new LogOutputStream() {
                @Override
                protected void processLine(String line) {
                  if (!filteredLogs.contains(line)) {
                    System.err.println(logPrefix + line);
                  }
                }
              })
              .destroyOnExit()
              .command(myArgs).execute();
        } catch (Throwable t) {
          Util.handleException(t);
        }
        FileUtils.deleteQuietly(tmpDir);
      }
    }).start();
    synchronized (ready) {
      while (!ready.get()) {
        try {
          ready.wait();
          break;
        } catch (InterruptedException e) {}
      }
    }
  }

  /**
   * Optionally call this method if you want JavaFX initialized and the browser
   * window opened immediately. Otherwise, initialization will happen lazily.
   */
  public void init() {
    try {
      remote.init();
    } catch (Throwable t) {
      Util.handleException(t);
    }
  }

  /**
   * Reset the state of the browser. More efficient than quitting the
   * browser and creating a new instance.
   * <p>
   * Note: it's not possible to switch between headless and GUI mode. You must quit this browser
   * and create a new instance.
   * 
   * @param settings
   *          New settings to take effect, superseding the original ones
   */
  public void reset(final Settings settings) {
    //TODO clear out tmp files except cache
    try {
      remote.reset(settings);
    } catch (Throwable t) {
      Util.handleException(t);
    }
  }

  /**
   * Reset the state of the browser. More efficient than quitting the
   * browser and creating a new instance.
   * <p>
   * Note: it's not possible to switch between headless and GUI mode. You must quit this browser
   * and create a new instance.
   * 
   * @param capabilities
   *          Capabilities to take effect, superseding the original ones
   */
  public void reset(Capabilities capabilities) {
    //TODO clear out tmp files except cache
    Settings settings = Settings.builder().build(capabilities);
    try {
      remote.reset(settings);
    } catch (Throwable t) {
      Util.handleException(t);
    }
    if (!(capabilities instanceof Serializable)) {
      capabilities = new DesiredCapabilities(capabilities);
    }
    try {
      remote.storeCapabilities(capabilities);
    } catch (Throwable t) {
      Util.handleException(t);
    }
  }

  /**
   * Reset the state of the browser. More efficient than quitting the
   * browser and creating a new instance.
   */
  public void reset() {
    try {
      remote.reset();
    } catch (Throwable t) {
      Util.handleException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getPageSource() {
    try {
      return remote.getPageSource();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getCurrentUrl() {
    try {
      return remote.getCurrentUrl();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * @return Status code of the response
   */
  public int getStatusCode() {
    try {
      return remote.getStatusCode();
    } catch (Throwable t) {
      Util.handleException(t);
      return -1;
    }
  }

  /**
   * Waits until requests are completed and idle for a certain
   * amount of time. This type of waiting happens implicitly on form
   * submissions, page loads, and mouse clicks, so in those cases there's
   * usually no need to call this method. However, calling this method
   * may be useful when requests are triggered under other circumstances or if
   * a more conservative wait is needed in addition to the implicit wait.
   * <p>
   * The behavior of this wait algorithm can be configured by
   * {@link Settings.Builder#ajaxWait(long)} and
   * {@link Settings.Builder#ajaxResourceTimeout(long)}.
   */
  public void pageWait() {
    try {
      remote.pageWait();
    } catch (Throwable t) {
      Util.handleException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTitle() {
    try {
      return remote.getTitle();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void get(final String url) {
    try {
      remote.get(url);
    } catch (Throwable t) {
      Util.handleException(t);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElement(By by) {
    try {
      return Element.constructElement(remote.findElement(by), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElements(By by) {
    try {
      return Element.constructList(remote.findElements(by), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElementById(String id) {
    try {
      return Element.constructElement(remote.findElementById(id), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElementsById(String id) {
    try {
      return Element.constructList(remote.findElementsById(id), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElementByXPath(String expr) {
    try {
      return Element.constructElement(remote.findElementByXPath(expr), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElementsByXPath(String expr) {
    try {
      return Element.constructList(remote.findElementsByXPath(expr), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElementByLinkText(final String text) {
    try {
      return Element.constructElement(remote.findElementByLinkText(text), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElementByPartialLinkText(String text) {
    try {
      return Element.constructElement(remote.findElementByPartialLinkText(text), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElementsByLinkText(String text) {
    try {
      return Element.constructList(remote.findElementsByLinkText(text), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElementsByPartialLinkText(String text) {
    try {
      return Element.constructList(remote.findElementsByPartialLinkText(text), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElementByClassName(String cssClass) {
    try {
      return Element.constructElement(remote.findElementByClassName(cssClass), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElementsByClassName(String cssClass) {
    try {
      return Element.constructList(remote.findElementsByClassName(cssClass), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElementByName(String name) {
    try {
      return Element.constructElement(remote.findElementByName(name), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElementsByName(String name) {
    try {
      return Element.constructList(remote.findElementsByName(name), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElementByCssSelector(String expr) {
    try {
      return Element.constructElement(remote.findElementByCssSelector(expr), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElementsByCssSelector(String expr) {
    try {
      return Element.constructList(remote.findElementsByCssSelector(expr), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public WebElement findElementByTagName(String tagName) {
    try {
      return Element.constructElement(remote.findElementByTagName(tagName), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<WebElement> findElementsByTagName(String tagName) {
    try {
      return Element.constructList(remote.findElementsByTagName(tagName), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return new ArrayList<WebElement>();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object executeAsyncScript(String script, Object... args) {
    try {
      return Element.constructObject(remote.executeAsyncScript(script, Element.scriptParams(args)), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object executeScript(String script, Object... args) {
    try {
      return Element.constructObject(remote.executeScript(script, Element.scriptParams(args)), this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public org.openqa.selenium.interactions.Keyboard getKeyboard() {
    try {
      KeyboardRemote keyboard = remote.getKeyboard();
      if (keyboard == null) {
        return null;
      }
      return new Keyboard(keyboard);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public org.openqa.selenium.interactions.Mouse getMouse() {
    try {
      MouseRemote mouse = remote.getMouse();
      if (mouse == null) {
        return null;
      }
      return new Mouse(mouse);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Capabilities getCapabilities() {
    try {
      return remote.getCapabilities();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() {
    try {
      remote.close();
      Set<String> handles = getWindowHandles();
      if (handles == null || handles.isEmpty()) {
        quit();
      }
    } catch (Throwable t) {
      try {
        remote.kill();
      } catch (Throwable t2) {}
      endProcess();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getWindowHandle() {
    try {
      return remote.getWindowHandle();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<String> getWindowHandles() {
    try {
      return remote.getWindowHandles();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Options manage() {
    if (options.get() == null) {
      try {
        OptionsRemote optionsRemote = remote.manage();
        if (optionsRemote == null) {
          return null;
        }
        return new com.machinepublishers.jbrowserdriver.Options(optionsRemote, logs);
      } catch (Throwable t) {
        Util.handleException(t);
        return null;
      }
    } else {
      return options.get();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Navigation navigate() {
    try {
      NavigationRemote navigation = remote.navigate();
      if (navigation == null) {
        return null;
      }
      return new com.machinepublishers.jbrowserdriver.Navigation(navigation);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  private void endProcess() {
    try {
      PidProcess pidProcess = Processes.newPidProcess(process.get());
      if (!pidProcess.destroyGracefully().waitFor(10, TimeUnit.SECONDS)) {
        pidProcess.destroyForcefully();
      }
    } catch (Throwable t2) {
      process.get().destroyForcibly();
    }
    synchronized (childPortsAvailable) {
      childPortsAvailable.add(configuredChildPort.get());
      childPortsUsed.remove(configuredChildPort.get());
      childPortsAvailable.notifyAll();
    }
  }

  private void saveData() {
    try {
      OptionsRemote optionsRemote = remote.manage();
      Set<Cookie> cookiesLocal = optionsRemote.getCookies();
      LogsRemote logsRemote = optionsRemote.logs();
      final LogEntries entries = logsRemote.getRemote(null).toLogEntries();
      final Set<String> types = logsRemote.getAvailableLogTypes();
      org.openqa.selenium.logging.Logs logsLocal = new org.openqa.selenium.logging.Logs() {
        @Override
        public Set<String> getAvailableLogTypes() {
          return types;
        }

        @Override
        public LogEntries get(String logType) {
          return entries;
        }
      };
      options.set(new OptionsLocal(cookiesLocal, logsLocal));
    } catch (Throwable t) {}
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void quit() {
    saveData();
    try {
      remote.quit();
    } catch (Throwable t) {
      try {
        remote.kill();
      } catch (Throwable t2) {}
    }
    endProcess();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TargetLocator switchTo() {
    try {
      TargetLocatorRemote locator = remote.switchTo();
      if (locator == null) {
        return null;
      }
      return new com.machinepublishers.jbrowserdriver.TargetLocator(locator, this);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void kill() {
    saveData();
    try {
      remote.kill();
    } catch (Throwable t) {}
    endProcess();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <X> X getScreenshotAs(final OutputType<X> outputType) throws WebDriverException {
    try {
      byte[] bytes = remote.getScreenshot();
      if (bytes == null) {
        return null;
      }
      return outputType.convertFromPngBytes(bytes);
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * @return Temporary directory where cached pages are saved.
   */
  public File cacheDir() {
    try {
      return remote.cacheDir();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * @return Temporary directory where downloaded files are saved.
   */
  public File attachmentsDir() {
    try {
      return remote.attachmentsDir();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * @return Temporary directory where media files are saved.
   */
  public File mediaDir() {
    try {
      return remote.mediaDir();
    } catch (Throwable t) {
      Util.handleException(t);
      return null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SessionId getSessionId() {
    return sessionId;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ErrorHandler getErrorHandler() {
    return super.getErrorHandler();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CommandExecutor getCommandExecutor() {
    return super.getCommandExecutor();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FileDetector getFileDetector() {
    return super.getFileDetector();
  }
}
