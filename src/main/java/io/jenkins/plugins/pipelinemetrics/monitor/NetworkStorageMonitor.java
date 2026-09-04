package io.jenkins.plugins.pipelinemetrics.monitor;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import io.jenkins.plugins.pipelinemetrics.config.PipelineMetricsGlobalConfig;
import io.jenkins.plugins.pipelinemetrics.store.backend.SQLiteStorageBackend;
import io.jenkins.plugins.pipelinemetrics.store.backend.StorageBackend;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import jenkins.model.Jenkins;

/**
 * Warns (but never auto-switches anything) when the active backend is local SQLite and
 * {@code $JENKINS_HOME/pipeline-metrics} appears to sit on a network filesystem (NFS/EFS/CIFS/
 * ...). SQLite — especially the WAL journal mode this plugin uses — depends on POSIX
 * shared-memory and byte-range locking semantics many network filesystems don't honor
 * correctly, which can mean write stalls or corruption rather than just slowness. This is
 * exactly the "detect, warn, never auto-switch" contract: detection failure or ambiguity always
 * resolves to "not activated" rather than misfiring or blocking startup.
 */
@Extension
public class NetworkStorageMonitor extends AdministrativeMonitor {

    /**
     * Conservative allow-list of {@link java.nio.file.FileStore#type()} values known to be
     * network filesystems. Not exhaustive or fully standardized across CSI drivers/OSes — this
     * is a best-effort heuristic, which is exactly why it only warns and never gates behavior.
     */
    static final Set<String> NETWORK_FS_TYPES = new HashSet<>(Arrays.asList(
            "nfs", "nfs3", "nfs4", "nfsd", "cifs", "smb", "smb2", "smbfs",
            "glusterfs", "ceph", "9p", "afs", "fuse.sshfs", "fuse.efs"));

    @Override
    public boolean isActivated() {
        PipelineMetricsGlobalConfig config = PipelineMetricsGlobalConfig.get();
        if (config == null) {
            return false;
        }
        StorageBackend backend = config.getStorageBackend();
        if (!(backend instanceof SQLiteStorageBackend)) {
            return false;
        }
        return isNetworkFilesystemType(detectFileStoreType());
    }

    static boolean isNetworkFilesystemType(String fsType) {
        return fsType != null && NETWORK_FS_TYPES.contains(fsType.toLowerCase(Locale.ROOT));
    }

    private static String detectFileStoreType() {
        try {
            File dir = new File(Jenkins.get().getRootDir(), "pipeline-metrics");
            Files.createDirectories(dir.toPath());
            return Files.getFileStore(dir.toPath()).type();
        } catch (IOException | RuntimeException e) {
            // Best-effort: an inability to detect the filesystem type must never misfire the
            // warning, and must never delay or break Jenkins startup.
            return null;
        }
    }

    /** Best-effort hint about the surrounding environment, for the warning message only. */
    @NonNull
    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
            justification = "/.dockerenv is a well-known, deliberate Linux/Docker marker file check")
    public String environmentHint() {
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            return "This looks like a Kubernetes pod.";
        }
        if (System.getenv("ECS_CONTAINER_METADATA_URI_V4") != null
                || "AWS_ECS_FARGATE".equals(System.getenv("AWS_EXECUTION_ENV"))) {
            return "This looks like an AWS ECS/Fargate task.";
        }
        if (new File("/.dockerenv").exists()) {
            return "This looks like a Docker container.";
        }
        return "";
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "Pipeline Metrics: network-backed storage detected";
    }
}
