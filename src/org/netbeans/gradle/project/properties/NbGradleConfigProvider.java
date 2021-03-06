package org.netbeans.gradle.project.properties;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.spi.project.ProjectConfigurationProvider;
import org.openide.util.ChangeSupport;

public final class NbGradleConfigProvider implements ProjectConfigurationProvider<NbGradleConfiguration> {
    private static final Logger LOGGER = Logger.getLogger(NbGradleConfigProvider.class.getName());

    private static final Lock CONFIG_PROVIDERS_LOCK = new ReentrantLock();
    private static final Map<File, NbGradleConfigProvider> CONFIG_PROVIDERS
            = new WeakValueHashMap<File, NbGradleConfigProvider>();

    private final File rootDirectory;
    private final PropertyChangeSupport changeSupport;
    private final ChangeSupport activeConfigChanges;
    private final Set<NbGradleConfiguration> configs;
    private final AtomicReference<NbGradleConfiguration> activeConfig;
    private final AtomicBoolean hasBeenUsed;

    public NbGradleConfigProvider(File rootDirectory) {
        if (rootDirectory == null) throw new NullPointerException("rootDirectory");

        this.rootDirectory = rootDirectory;
        this.hasBeenUsed = new AtomicBoolean(false);
        this.changeSupport = new PropertyChangeSupport(this);
        this.activeConfigChanges = new ChangeSupport(this);
        this.activeConfig = new AtomicReference<NbGradleConfiguration>(NbGradleConfiguration.DEFAULT_CONFIG);
        this.configs = Collections.synchronizedSet(new LinkedHashSet<NbGradleConfiguration>());
        this.configs.add(NbGradleConfiguration.DEFAULT_CONFIG);
    }

    public static NbGradleConfigProvider getConfigProvider(NbGradleProject project) {
        File rootDir = SettingsFiles.getRootDirectory(project);

        CONFIG_PROVIDERS_LOCK.lock();
        try {
            NbGradleConfigProvider result = CONFIG_PROVIDERS.get(rootDir);
            if (result == null) {
                result = new NbGradleConfigProvider(rootDir);
                CONFIG_PROVIDERS.put(rootDir, result);
            }
            return result;
        } finally {
            CONFIG_PROVIDERS_LOCK.unlock();
        }
    }

    public void removeConfiguration(final NbGradleConfiguration config) {
        if (config.getProfileName() == null) {
            LOGGER.warning("Cannot remove the default configuration");
            return;
        }

        configs.remove(config);
        NbGradleProject.PROJECT_PROCESSOR.execute(new Runnable() {
            @Override
            public void run() {
                File profileFile = SettingsFiles.getFilesForProfile(rootDirectory, config.getProfileName())[0];
                if (profileFile.isFile()) {
                    profileFile.delete();
                }
            }
        });
        executeOnEdt(new Runnable() {
            @Override
            public void run() {
                changeSupport.firePropertyChange(PROP_CONFIGURATIONS, null, null);
            }
        });
    }

    public void addConfiguration(NbGradleConfiguration config) {
        configs.add(config);
        executeOnEdt(new Runnable() {
            @Override
            public void run() {
                changeSupport.firePropertyChange(PROP_CONFIGURATIONS, null, null);
            }
        });
    }

    private void executeOnEdt(Runnable task) {
        assert task != null;

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        }
        else {
            SwingUtilities.invokeLater(task);
        }
    }

    public Collection<NbGradleConfiguration> findAndUpdateConfigurations(boolean mayRemove) {
        Collection<String> profileNames = SettingsFiles.getAvailableProfiles(rootDirectory);
        Collection<NbGradleConfiguration> currentConfigs
                = new ArrayList<NbGradleConfiguration>(profileNames.size() + 1);

        currentConfigs.add(NbGradleConfiguration.DEFAULT_CONFIG);
        for (String profileName: profileNames) {
            currentConfigs.add(new NbGradleConfiguration(profileName));
        }
        configs.addAll(currentConfigs);
        if (mayRemove) {
            configs.retainAll(currentConfigs);
        }
        if (!configs.contains(activeConfig.get())) {
            setActiveConfiguration(NbGradleConfiguration.DEFAULT_CONFIG);
        }

        executeOnEdt(new Runnable() {
            @Override
            public void run() {
                changeSupport.firePropertyChange(PROP_CONFIGURATIONS, null, null);
            }
        });
        return Collections.unmodifiableSet(configs);
    }

    private void ensureLoadedAsynchronously() {
        if (hasBeenUsed.compareAndSet(false, true)) {
            NbGradleProject.PROJECT_PROCESSOR.execute(new Runnable() {
                @Override
                public void run() {
                    findAndUpdateConfigurations(false);
                }
            });
        }
    }

    @Override
    public Collection<NbGradleConfiguration> getConfigurations() {
        ensureLoadedAsynchronously();
        return Collections.unmodifiableSet(configs);
    }

    @Override
    public NbGradleConfiguration getActiveConfiguration() {
        ensureLoadedAsynchronously();

        NbGradleConfiguration result = activeConfig.get();
        if (!configs.contains(result)) {
            result = NbGradleConfiguration.DEFAULT_CONFIG;
            setActiveConfiguration(result);
        }
        return result;
    }

    @Override
    public void setActiveConfiguration(final NbGradleConfiguration configuration) {
        if (configuration == null) {
            LOGGER.warning("Attempting to set null configuration.");
            return;
        }

        final NbGradleConfiguration prevConfig = activeConfig.getAndSet(configuration);
        if (!prevConfig.equals(configuration)) {
            executeOnEdt(new Runnable() {
                @Override
                public void run() {
                    changeSupport.firePropertyChange(PROP_CONFIGURATION_ACTIVE, prevConfig, configuration);
                    activeConfigChanges.fireChange();
                }
            });
        }
    }

    @Override
    public boolean hasCustomizer() {
        // TODO: Open the project properties window
        return false;
    }

    @Override
    public void customize() {
    }

    @Override
    public boolean configurationsAffectAction(String command) {
        return AbstractProjectProperties.getCustomizableCommands().contains(command);
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener lst) {
        changeSupport.addPropertyChangeListener(lst);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener lst) {
        changeSupport.removePropertyChangeListener(lst);
    }

    public void addActiveConfigChangeListener(ChangeListener listener) {
        activeConfigChanges.addChangeListener(listener);
    }

    public void removeActiveConfigChangeListener(ChangeListener listener) {
        activeConfigChanges.removeChangeListener(listener);
    }
}
