package org.jenkinsci.test.acceptance.po;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.jenkinsci.test.acceptance.slave.SlaveController;
import org.jenkinsci.utils.process.CommandBuilder;

/**
 * Built-in standard slave type.
 *
 * To create a new slave into a test, use {@link SlaveController}.
 *
 * @author Kohsuke Kawaguchi
 */
public class DumbSlave extends Slave {
    // config page elements
    public final Control description = control("/nodeDescription");
    public final Control executors = control("/numExecutors");
    public final Control remoteFS = control("/remoteFS");
    public final Control labels = control("/labelString");
    public final Control launchMethod = control("/");   // TODO: this path looks rather buggy to me

    public DumbSlave(Jenkins j, String name) {
        super(j, name);
    }

    /**
     * Selects the specified launcher, and return the page object to bind to it.
     */
    public <T extends ComputerLauncher> T setLauncher(Class<T> type) {
        findCaption(type, new Resolver() {
            @Override protected void resolve(String caption) {
                launchMethod.select(caption);
            }
        });
        return newInstance(type, this, "/launcher");
    }

    /**
     * Set up this slave as a local slave that launches slave on the same host as Jenkins
     * call this in the context of the config UI.
     */
    public void asLocal() {
        assertCurlOrPowershellv3Plus();
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        File jar = new File(tmpDir, "slave"+createRandomName()+".jar");
        String command;
        if (SystemUtils.IS_OS_UNIX) {
            String java = "java";
            String jh = System.getProperty("java.home");
            if (!StringUtils.isEmpty(jh)) {
                final File file = new File(jh, "bin/java");
                if (file.canExecute()) {
                    java = file.getAbsolutePath();
                }
            }
            command = String.format(
                    "sh -c 'curl -s -o %1$s %2$sjnlpJars/slave.jar && \"%3$s\" -jar %1$s'",
                    jar, url("../../"), java
            );
        }
        else {
            // windows
            command = String.format("powershell -command \"& { " +
                                                               "try { " +
                                                                  "Invoke-WebRequest %2$sjnlpJars/slave.jar -OutFile %1$s " +
                                                               "} catch { " +
                                                                  "echo 'download of slave jar failed'; " +
                                                                  "exit 2 "+
                                                               "} " +
                                                               "java -jar %1$s "+
                                                              "}\"",
                                    jar, url("../../")
                                   );
        }
        setLauncher(CommandSlaveLauncher.class).command(command);
    }

    private void assertCurlOrPowershellv3Plus() {
        try {
            if (SystemUtils.IS_OS_UNIX) {
                if (new CommandBuilder("which", "curl").system() != 0) {
                    throw new IllegalStateException("curl is required to run tests that run on local slaves.");
                }
            }
            else {
                if (new CommandBuilder("powershell -command \"& { Invoke-WebRequest -?}\"").system() != 0) {
                    // Invoke-WebRequest was introduced in version 3.
                    throw new IllegalStateException("powershell version 3 or higher is required to run tests that run on local slaves.");
                }
            }
        }
        catch (IOException | InterruptedException e) {
            // ignore and assume that curl is installed
        }
    }
}
