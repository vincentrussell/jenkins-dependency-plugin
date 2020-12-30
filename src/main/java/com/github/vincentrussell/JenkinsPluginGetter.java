package com.github.vincentrussell;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.project.deploy.ProjectDeployer;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import static org.apache.commons.lang3.Validate.notNull;

public class JenkinsPluginGetter {

    private final ProjectDeployer projectDeployer;
    private final ProjectBuilder projectBuilder;
    private final MavenSession mavenSession;
    private final MavenProjectHelper projectHelper;
    private final Log log;
    private final RepositorySystem repositorySystem;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String packaging;
    private final ArtifactResolver artifactResolver;
    private final String jenkinsPluginServerUrl;
    private final File downloadDir;

    private JenkinsPluginGetter(final Builder builder) {
        this.projectDeployer = builder.projectDeployer;
        this.projectBuilder = builder.projectBuilder;
        this.mavenSession = builder.mavenSession;
        this.projectHelper = builder.projectHelper;
        this.log = builder.log;
        this.repositorySystem = builder.repositorySystem;
        this.groupId = builder.groupId;
        this.artifactId = builder.artifactId;
        this.version = builder.version;
        this.packaging = builder.packaging;
        this.artifactResolver = builder.artifactResolver;
        this.jenkinsPluginServerUrl = builder.jenkinsPluginServerUrl;
        this.downloadDir = builder.downloadDir;
    }

    public boolean execute() throws IOException, ArtifactResolverException {
        notNull(projectDeployer, "projectDeployer is null");
        notNull(projectBuilder, "projectBuilder is null");
        notNull(mavenSession, "mavenSession is null");
        notNull(projectHelper, "projectHelper is null");
        notNull(repositorySystem, "repositorySystem is null");
        notNull(artifactResolver, "artifactResolver is null");
        notNull(jenkinsPluginServerUrl, "jenkinsPluginServerUrl is null");
        notNull(downloadDir, "downloadDir is null");

        Artifact artifactToDownload = repositorySystem.createArtifact( groupId, artifactId, version, packaging );

        Set<String> alreadyDownloadedArtifacts = new HashSet<>();
        recursiveDownload(alreadyDownloadedArtifacts, artifactToDownload);
        return true;
    }

    private void recursiveDownload(final Set<String> alreadyDownloadedArtifacts,
                                   final Artifact artifactToDownload) throws ArtifactResolverException, IOException {
        log.info("Resolving " + artifactToDownload);
        ArtifactResult result = null;
        try {
            result = artifactResolver.resolveArtifact(mavenSession.getProjectBuildingRequest(), artifactToDownload);
            if (!result.getArtifact().getFile().exists()) {
                throw new ArtifactResolverException("could not download file from remote repository", new Exception());

            }

        } catch (ArtifactResolverException e) {
            log.warn("could not download from remote remove repository " + e.getMessage());
            result = downloadArtifactFromPluginServer(artifactToDownload);
        }

        File resultFile = result.getArtifact().getFile();
        if (resultFile.exists()) {
            saveFileToDownloadDirectory(result.getArtifact());
            alreadyDownloadedArtifacts.add(result.getArtifact().toString());
            List<Artifact> dependenciesFromHpi = getHpiDependencies(repositorySystem, resultFile);
            for (Artifact artifact : dependenciesFromHpi) {
                if (!alreadyDownloadedArtifacts.contains(artifact.toString())) {
                    recursiveDownload(alreadyDownloadedArtifacts, artifact);
                }
            }
        }
    }

    private void saveFileToDownloadDirectory(Artifact artifact) throws IOException {
        List<String> directoryParts = new ArrayList<>();
        directoryParts.addAll(Lists.newArrayList(artifact.getGroupId().trim().split("\\.")));
        directoryParts.add(artifact.getArtifactId().trim());
        directoryParts.add(artifact.getVersion().trim());

        final File file = new File(Paths.get(downloadDir.getAbsolutePath(), directoryParts.toArray(new String[0])).toFile(),
                artifact.getArtifactId() + "-" + artifact.getVersion() + "." + artifact.getType());
        FileUtils.forceMkdir(file.getParentFile());
        if (file.exists()) {
            FileUtils.deleteQuietly(file);
        }

        FileUtils.copyFile(artifact.getFile(), file);

    }

    private ArtifactResult downloadArtifactFromPluginServer(final Artifact toDownload) throws IOException, ArtifactResolverException {
        ArtifactResult result;
        ProjectBuildingRequest request = mavenSession.getProjectBuildingRequest();
        ArtifactRepository localRepository = request.getLocalRepository();

        List<String> directoryParts = new ArrayList<>();
        directoryParts.addAll(Lists.newArrayList(toDownload.getGroupId().trim().split("\\.")));
        directoryParts.add(toDownload.getArtifactId().trim());
        directoryParts.add(toDownload.getVersion().trim());

        final File file = new File(Paths.get(localRepository.getBasedir(),
                directoryParts.toArray(new String[0])).toFile(),
                toDownload.getArtifactId() + "-" +
                        toDownload.getVersion() + "." + toDownload.getType());
        FileUtils.forceMkdir(file.getParentFile());

        String urlToDownloadFrom = String.format("%s/%s/%s/%s.hpi", jenkinsPluginServerUrl.replaceAll("/$", ""),
                toDownload.getArtifactId(), toDownload.getVersion(), toDownload.getArtifactId());

        try (CloseableHttpClient client = HttpClients.createDefault();
             FileOutputStream fileOutputStream = new FileOutputStream(file)) {
            log.info("about to download from " + urlToDownloadFrom);
            HttpGet httpGet = new HttpGet(urlToDownloadFrom);

            try (CloseableHttpResponse response = client.execute(httpGet)) {
                if (HttpStatus.SC_OK == response.getStatusLine().getStatusCode()) {
                    IOUtils.copy(response.getEntity().getContent(), fileOutputStream);
                    result = new ArtifactResult() {
                        @Override
                        public Artifact getArtifact() {
                            DefaultArtifact defaultArtifact = new DefaultArtifact(toDownload.getGroupId(),
                                    toDownload.getArtifactId(), toDownload.getVersion(), toDownload.getScope(),
                                    toDownload.getType(), toDownload.getClassifier(), toDownload.getArtifactHandler());
                            defaultArtifact.setFile(file);
                            return defaultArtifact;
                        }
                    };
                } else {
                    Exception exception = new IOException("could not download plugin from " + urlToDownloadFrom);
                    log.error(exception);
                    throw new ArtifactResolverException(exception.getMessage(), exception);
                }
            }
        }
        return result;
    }

    private List<Artifact> getHpiDependencies(final RepositorySystem repositorySystem, final File resultFile) throws IOException {
        try (JarInputStream jarInputStream = new JarInputStream(new FileInputStream(resultFile))) {
            Manifest manifest = jarInputStream.getManifest();
            String dependencies = manifest.getMainAttributes().getValue("Plugin-Dependencies");
            if (dependencies == null) {
                return Collections.emptyList();
            }

            log.info( "Found dependencies " + dependencies );
            String[] deps = dependencies.split(",");
            return Lists.transform(Lists.newArrayList(deps), new Function<String, Artifact>() {
                @Override
                @SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
                public Artifact apply(final String input) {
                    final String inputWithoutOptionalParameters = input.split(";")[0];
                    String[] tokens = StringUtils.split(inputWithoutOptionalParameters, ":" );
                    String groupId = null;
                    String artifactId = null;
                    String version = null;
                    String packaging = "hpi";

                    if (tokens.length == 2) {
                        groupId = "org.jenkins-ci.plugins";
                        artifactId = tokens[0];
                        version = tokens[1];
                    } else if (tokens.length == 3) {
                        groupId = tokens[0];
                        artifactId = tokens[1];
                        version = tokens[2];
                    }

                    Artifact toDownload = repositorySystem.createArtifact( groupId, artifactId, version, packaging );
                    return toDownload;
                }
            });
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static class Builder {
        private ProjectDeployer projectDeployer;
        private ProjectBuilder projectBuilder;
        private MavenSession mavenSession;
        private MavenProjectHelper projectHelper;
        private Log log;
        private String groupId;
        private String artifactId;
        private String version;
        private String packaging = "hpi";
        private RepositorySystem repositorySystem;
        private ArtifactResolver artifactResolver;
        private String jenkinsPluginServerUrl;
        private File downloadDir;

        public Builder setProjectDeployer(ProjectDeployer projectDeployer) {
            this.projectDeployer = projectDeployer;
            return this;
        }


        public Builder setGroupId(String groupId) {
            if (groupId != null) {
                this.groupId = groupId;
            }
            return this;
        }

        public Builder setArtifactId(String artifactId) {
            if (artifactId != null) {
                this.artifactId = artifactId;
            }
            return this;
        }

        public Builder setDownloadDir(File downloadDir) {
            this.downloadDir = downloadDir;
            return this;
        }

        public Builder setJenkinsPluginServerUrl(String jenkinsPluginServerUrl) {
            this.jenkinsPluginServerUrl = jenkinsPluginServerUrl;
            return this;
        }

        public Builder setVersion(String version) {
            if (version != null) {
                this.version = version;
            }
            return this;
        }

        public Builder setArtifact(String artifact) throws MojoFailureException {

            if ( artifact != null ) {
                String[] tokens = StringUtils.split( artifact, ":" );
                if (tokens.length == 2) {
                    groupId = "org.jenkins-ci.plugins";
                    artifactId = tokens[0];
                    version = tokens[1];
                    packaging = "hpi";
                    return this;
                } else if ( tokens.length < 3 || tokens.length > 5 ) {
                    throw new MojoFailureException(
                            "Invalid artifact, you must specify groupId:artifactId:version[:packaging][:classifier] "
                                    + artifact );
                }
                groupId = tokens[0];
                artifactId = tokens[1];
                version = tokens[2];
                if ( tokens.length >= 4 ) {
                    packaging = tokens[3];
                }
            }

            return this;
        }

        public Builder setProjectBuilder(ProjectBuilder projectBuilder) {
            this.projectBuilder = projectBuilder;
            return this;
        }

        public Builder setMavenSession(MavenSession mavenSession) {
            this.mavenSession = mavenSession;
            return this;
        }

        public Builder setProjectHelper(MavenProjectHelper projectHelper) {
            this.projectHelper = projectHelper;
            return this;
        }

        public Builder setLogger(Log log) {
            this.log =log;
            return this;
        }

        public Builder setRepositorySystem(RepositorySystem repositorySystem) {
            this.repositorySystem = repositorySystem;
            return this;
        }

        public Builder setArtifactResolver(ArtifactResolver artifactResolver) {
            this.artifactResolver = artifactResolver;
            return this;
        }

        public JenkinsPluginGetter build() throws MojoFailureException {

            if ( artifactId == null ||  groupId == null || version == null  || packaging == null )
            {
                throw new MojoFailureException( "You must specify an artifact, "
                        + "e.g. -Dartifact=org.apache.maven.plugins:maven-downloader-plugin:1.0" );
            }




            return new JenkinsPluginGetter(this);
        }

    }
}