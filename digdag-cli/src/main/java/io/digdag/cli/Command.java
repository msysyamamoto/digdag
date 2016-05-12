package io.digdag.cli;

import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.io.IOException;

import io.digdag.core.config.PropertyUtils;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.DynamicParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Command
{
    private static final Logger log = LoggerFactory.getLogger(Command.class);

    protected final PrintStream out;
    protected final PrintStream err;
    protected final Context ctx;

    @Parameter()
    protected List<String> args = new ArrayList<>();

    @Parameter(names = {"-c", "--config"})
    protected String configPath = null;

    @Parameter(names = {"-L", "--log"})
    protected String logPath = "-";

    @Parameter(names = {"-l", "--log-level"})
    protected String logLevel = "info";

    @DynamicParameter(names = "-X")
    protected Map<String, String> systemProperties = new HashMap<>();

    @Parameter(names = {"-help", "--help"}, help = true, hidden = true)
    protected boolean help;

    protected Command(Context ctx)
    {
        this.out = ctx.out();
        this.err = ctx.err();
        this.ctx = ctx;
    }

    public abstract void main() throws Exception;

    public abstract SystemExitException usage(String error, Context ctx);

    protected Properties loadSystemProperties()
        throws IOException
    {
        Properties props;

        // Load specific configuration file, if specified.
        if (configPath != null) {
            props = PropertyUtils.loadFile(Paths.get(configPath));
        } else {
            // If no configuration file was specified, load the default configuration, if it exists.
            try {
                props = PropertyUtils.loadFile(ConfigUtil.defaultConfigPath(ctx));
            }
            catch (NoSuchFileException ex) {
                log.trace("configuration file not found: {}", configPath, ex);
                props = new Properties();
            }
        }

        // Override properties from config file with system properties
        props.putAll(System.getProperties());

        return props;
    }
}
