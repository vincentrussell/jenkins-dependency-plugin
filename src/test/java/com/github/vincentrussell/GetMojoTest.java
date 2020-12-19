package com.github.vincentrussell;

import com.google.common.collect.Lists;
import me.alexpanov.net.FreePortFinder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.Authentication;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.Proxy;
import org.codehaus.plexus.archiver.jar.Manifest;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class GetMojoTest extends AbstractMojoTestCase {

    public static final String PLUGIN_SERVER_DIR = "pluginServer";
    public static final String NEXUS_URL_REPOSITORY_THIRDPARTY = "/repository/thirdparty/";
    private int httpPort = FreePortFinder.findFreeLocalPort();
    private Server jettyServer;

    @Rule
    TemporaryFolder temporaryFolder = new TemporaryFolder();

    String groupId = "com.github.vincentrussell";
    String artifactId = "cool-artifact";
    String releaseVersion = "1.0";
    String snapshotVersion = "1.0-SNAPSHOT";

    File jettyNexusBaseDir;
    File localBaseDir;
    ArtifactRepository localRepo;
    File localReleaseArtifactDir;
    File remoteReleaseArtifactDir;
    File localSnapshotArtifactDir;
    File remoteSnapshotArtifactDir;

    @Override
    protected void setUp() throws Exception {
        temporaryFolder.create();
        jettyNexusBaseDir = temporaryFolder.newFolder("jetty-remote");
        localBaseDir = temporaryFolder.newFolder("local-base-dir");
        localRepo = createLocalArtifactRepository(localBaseDir);
        jettyServer = new Server();
        ServerConnector httpConnector = new ServerConnector(jettyServer);
        ServletHandler servletHandler = new ServletHandler();
        NexusServlet nexusServlet = new NexusServlet(jettyNexusBaseDir);
        ServletHolder servletHolder = new ServletHolder(nexusServlet);
        servletHandler.addServletWithMapping(servletHolder, NEXUS_URL_REPOSITORY_THIRDPARTY + "*");
        servletHandler.addServletWithMapping(servletHolder, "/"+ PLUGIN_SERVER_DIR + "/*");
        httpConnector.setPort(httpPort);
        jettyServer.setConnectors(new Connector[] {httpConnector});
        jettyServer.setHandler(servletHandler);
        jettyServer.start();
        localReleaseArtifactDir = getBaseDirectoryForArtifact(localBaseDir, groupId, artifactId, releaseVersion);
        remoteReleaseArtifactDir = getBaseDirectoryForArtifact(jettyNexusBaseDir, groupId, artifactId, releaseVersion);
        localSnapshotArtifactDir = getBaseDirectoryForArtifact(localBaseDir, groupId, artifactId, snapshotVersion);
        remoteSnapshotArtifactDir = getBaseDirectoryForArtifact(jettyNexusBaseDir, groupId, artifactId, snapshotVersion);
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception {
        jettyServer.stop();
        temporaryFolder.delete();
        super.tearDown();
    }

    @Test
    public void testDownloadArtifact() throws Exception {
        File downloadDir = temporaryFolder.newFolder();

        String config = " <artifact>com.github.vincentrussell:jenkins-plugin1:1.1:hpi</artifact>\n" +
                "<downloadDir>"+ downloadDir.getAbsolutePath() + "</downloadDir>";

        String url = "http://localhost:" + httpPort + NEXUS_URL_REPOSITORY_THIRDPARTY;
        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config, url).getParentFile(), url);
        MavenSession session = finishSessionCreation(newMavenSession( mavenProject ), mavenProject.getRemoteArtifactRepositories());
        createMavenFiles(jettyNexusBaseDir, "com.github.vincentrussell", "jenkins-plugin1", "1.1", "com.github.vincentrussell:jenkins-plugin1:1.2,com.github.vincentrussell:jenkins-plugin1:1.3");
        createMavenFiles(jettyNexusBaseDir, "com.github.vincentrussell", "jenkins-plugin1", "1.2", "com.github.vincentrussell:jenkins-plugin1:1.1");
        createMavenFiles(jettyNexusBaseDir, "com.github.vincentrussell", "jenkins-plugin1", "1.3", "com.github.vincentrussell:jenkins-plugin1:1.1");

        MojoExecution execution = newMojoExecution( "get" );
        GetMojo getMojo = (GetMojo) lookupConfiguredMojo( session, execution );

        assertTrue(downloadDir.listFiles().length == 0);

        getMojo.execute();

        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "com/github/vincentrussell/jenkins-plugin1/1.1/jenkins-plugin1-1.1.hpi").toFile().exists());
        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "com/github/vincentrussell/jenkins-plugin1/1.2/jenkins-plugin1-1.2.hpi").toFile().exists());
        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "com/github/vincentrussell/jenkins-plugin1/1.3/jenkins-plugin1-1.3.hpi").toFile().exists());
    }

    @Test
    public void testDownloadArtifactWithoutSpecifyingGroupId() throws Exception {
        File downloadDir = temporaryFolder.newFolder();

        String config = " <artifact>jenkins-plugin1:1.1</artifact>\n" +
                "<downloadDir>"+ downloadDir.getAbsolutePath() + "</downloadDir>";

        String url = "http://localhost:" + httpPort + NEXUS_URL_REPOSITORY_THIRDPARTY;
        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config, url).getParentFile(), url);
        MavenSession session = finishSessionCreation(newMavenSession( mavenProject ), mavenProject.getRemoteArtifactRepositories());
        createMavenFiles(jettyNexusBaseDir, "org.jenkins-ci.plugins", "jenkins-plugin1", "1.1", "jenkins-plugin1:1.2,jenkins-plugin1:1.3");
        createMavenFiles(jettyNexusBaseDir, "org.jenkins-ci.plugins", "jenkins-plugin1", "1.2", "jenkins-plugin1:1.1");
        createMavenFiles(jettyNexusBaseDir, "org.jenkins-ci.plugins", "jenkins-plugin1", "1.3", "jenkins-plugin1:1.1");

        MojoExecution execution = newMojoExecution( "get" );
        GetMojo getMojo = (GetMojo) lookupConfiguredMojo( session, execution );

        assertTrue(downloadDir.listFiles().length == 0);

        getMojo.execute();

        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "org/jenkins-ci/plugins/jenkins-plugin1/1.1/jenkins-plugin1-1.1.hpi").toFile().exists());
        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "org/jenkins-ci/plugins/jenkins-plugin1/1.2/jenkins-plugin1-1.2.hpi").toFile().exists());
        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "org/jenkins-ci/plugins/jenkins-plugin1/1.3/jenkins-plugin1-1.3.hpi").toFile().exists());
    }

    @Test
    public void testDownloadFromPluginServer() throws Exception {
        File downloadDir = temporaryFolder.newFolder();

        String config = " <artifact>jenkins-plugin1:1.1</artifact>\n" +
                "<downloadDir>"+ downloadDir.getAbsolutePath() + "</downloadDir>\n" +
                "<jenkinsPluginServerUrl>http://localhost:" + httpPort + "/pluginServer/</jenkinsPluginServerUrl>";

        String url = "http://localhost:" + httpPort + NEXUS_URL_REPOSITORY_THIRDPARTY;
        MavenProject mavenProject = readMavenProject(new TestProjectConfig(temporaryFolder).getFile(config, url).getParentFile(), url);
        MavenSession session = finishSessionCreation(newMavenSession( mavenProject ), mavenProject.getRemoteArtifactRepositories());
        createPluginServerFiles(jettyNexusBaseDir, "org.jenkins-ci.plugins", "jenkins-plugin1", "1.1", "jenkins-plugin1:1.2,jenkins-plugin1:1.3");
        createPluginServerFiles(jettyNexusBaseDir, "org.jenkins-ci.plugins", "jenkins-plugin1", "1.2", "jenkins-plugin1:1.1");
        createPluginServerFiles(jettyNexusBaseDir, "org.jenkins-ci.plugins", "jenkins-plugin1", "1.3", "jenkins-plugin1:1.1");

        MojoExecution execution = newMojoExecution( "get" );
        GetMojo getMojo = (GetMojo) lookupConfiguredMojo( session, execution );

        assertTrue(downloadDir.listFiles().length == 0);

        getMojo.execute();

        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "org/jenkins-ci/plugins/jenkins-plugin1/1.1/jenkins-plugin1-1.1.hpi").toFile().exists());
        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "org/jenkins-ci/plugins/jenkins-plugin1/1.2/jenkins-plugin1-1.2.hpi").toFile().exists());
        assertTrue(Paths.get(downloadDir.getAbsolutePath(), "org/jenkins-ci/plugins/jenkins-plugin1/1.3/jenkins-plugin1-1.3.hpi").toFile().exists());
    }

    private void createPluginServerFiles(File localBaseDir, String groupId, String artifactId, String version, String pluginDependencies) throws IOException, ManifestException {
        List<String> directoryParts = new ArrayList<>();
        directoryParts.add(version.trim());

        File directory = Paths.get(localBaseDir.getAbsolutePath(), PLUGIN_SERVER_DIR, artifactId, version).toFile();
        directory.mkdirs();
        File jarFile = new File(directory, artifactId + ".hpi");
        createHpiFile(jarFile, version, pluginDependencies);
    }


    private MavenSession finishSessionCreation(MavenSession newMavenSession, List<ArtifactRepository> remoteArtifactRepositories) throws NoLocalRepositoryManagerException {
        DefaultRepositorySystemSession defaultRepositorySystem = (DefaultRepositorySystemSession) newMavenSession.getRepositorySession();
        SimpleLocalRepositoryManagerFactory simpleLocalRepositoryManagerFactory = new SimpleLocalRepositoryManagerFactory();
        LocalRepositoryManager localRepositoryManager = simpleLocalRepositoryManagerFactory.newInstance(defaultRepositorySystem, new LocalRepository(localBaseDir));
        defaultRepositorySystem.setLocalRepositoryManager(localRepositoryManager);
        newMavenSession.getRequest().setLocalRepository(localRepo);
        for (ArtifactRepository artifactRepository : remoteArtifactRepositories) {
            newMavenSession.getRequest().addRemoteRepository(artifactRepository);
        }
        return newMavenSession;
    }

    private void createMavenFiles(File localBaseDir, String groupId, String artifactId, String version, String pluginDependencies) throws IOException, ManifestException {
        File theArtifactDir = getBaseDirectoryForArtifact(localBaseDir, groupId,  artifactId, version);
        theArtifactDir.mkdirs();
        File jarFile = new File(theArtifactDir, artifactId + "-" + version + ".hpi");
        createHpiFile(jarFile, version, pluginDependencies);
    }

    private File getBaseDirectoryForArtifact(File localBaseDir, String groupId, String artifactId, String version) {
        List<String> directoryParts = new ArrayList<>();
        directoryParts.addAll(Lists.newArrayList(groupId.trim().split("\\.")));
        directoryParts.add(artifactId.trim());
        directoryParts.add(version.trim());

        return Paths.get(localBaseDir.getAbsolutePath(), directoryParts.toArray(new String[0])).toFile();
    }

    private void createHpiFile(File jarFile, String version, String pluginDependencies) throws IOException, ManifestException {
        File newFile = temporaryFolder.newFile();
        FileUtils.writeByteArrayToFile(newFile, getRandomByteArray());


        Manifest manifest = new Manifest();
        manifest.addConfiguredAttribute(new Manifest.Attribute("Plugin-Version", version));
        manifest.addConfiguredAttribute(new Manifest.Attribute("Plugin-Dependencies", pluginDependencies));
        try (JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
            add(newFile, jarOutputStream);
        }
    }

    private void add(File source, JarOutputStream target) throws IOException
    {
        BufferedInputStream in = null;
        try
        {
            if (source.isDirectory())
            {
                String name = source.getPath().replace("\\", "/");
                if (!name.isEmpty())
                {
                    if (!name.endsWith("/"))
                        name += "/";
                    JarEntry entry = new JarEntry(name);
                    entry.setTime(source.lastModified());
                    target.putNextEntry(entry);
                    target.closeEntry();
                }
                for (File nestedFile: source.listFiles())
                    add(nestedFile, target);
                return;
            }

            JarEntry entry = new JarEntry(source.getPath().replace("\\", "/"));
            entry.setTime(source.lastModified());
            target.putNextEntry(entry);
            in = new BufferedInputStream(new FileInputStream(source));

            byte[] buffer = new byte[1024];
            while (true)
            {
                int count = in.read(buffer);
                if (count == -1)
                    break;
                target.write(buffer, 0, count);
            }
            target.closeEntry();
        }
        finally
        {
            if (in != null)
                in.close();
        }
    }

    private byte[] getRandomByteArray() {
        byte[] b = new byte[2000];
        new Random().nextBytes(b);
        return b;
    }

    private ArtifactRepository createLocalArtifactRepository(File localRepoDir) {
        return new MavenArtifactRepository("local",
                localRepoDir.toURI().toString(),
                new DefaultRepositoryLayout(),
                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)

        );
    }

    protected MavenProject readMavenProject(File basedir, String url)
            throws ProjectBuildingException, Exception
    {
        File pom = new File( basedir, "pom.xml" );
        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        ArtifactRepository artifactRepository = new ArtifactRepositoryAdapter(new RemoteRepository.Builder("default", "release", url).build());
        request.setRemoteRepositories(Lists.newArrayList(artifactRepository));
        request.setBaseDirectory( basedir );
        ProjectBuildingRequest configuration = request.getProjectBuildingRequest();
        DefaultRepositorySystemSession repositorySession = new DefaultRepositorySystemSession();
        configuration.setRepositorySession(repositorySession);
        MavenProject project = lookup( ProjectBuilder.class ).build( pom, configuration ).getProject();
        assertNotNull( project );
        return project;
    }



    public static class NexusServlet extends HttpServlet {

        private final File baseDir;

        public NexusServlet(File baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        protected void doGet(
                HttpServletRequest request,
                HttpServletResponse response)
                throws ServletException, IOException {

            File file = null;
            if (request.getRequestURI().contains(PLUGIN_SERVER_DIR)) {
                file = Paths.get(Paths.get(baseDir.toPath().toString(), PLUGIN_SERVER_DIR).toString(), request.getPathInfo()).toFile();
            } else {
                file = Paths.get(baseDir.getAbsolutePath(), request.getPathInfo()).toFile();
            }

            if (request.getRequestURI().endsWith("maven-metadata.xml")) {
                response.setContentType("application/xml");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().println("" +
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<metadata>\n" +
                        "  <groupId>com.github.vincentrussell</groupId>\n" +
                        "  <artifactId>doesnt-matter</artifactId>\n" +
                        "  <versioning>\n" +
                        "    <release>0.1.1</release>\n" +
                        "    <versions>\n" +
                        "      <version>0.1.1</version>\n" +
                        "    </versions>\n" +
                        "    <lastUpdated>20200608005752</lastUpdated>\n" +
                        "  </versioning>\n" +
                        "</metadata>\n");
            } else if (file.exists()) {
                try (FileInputStream fileInputStream = new FileInputStream(file)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    IOUtils.copy(fileInputStream, response.getOutputStream());
                    return;
                }
            }

            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }

        @Override
        protected void doPut(
                HttpServletRequest request,
                HttpServletResponse response)
                throws ServletException, IOException {

            String pathInfo = request.getPathInfo();

            File file = Paths.get(baseDir.getAbsolutePath(), request.getPathInfo()).toFile();
            file.getParentFile().mkdirs();

            try(FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                IOUtils.copy(request.getInputStream(), fileOutputStream);
            }

            response.setContentType("plain/text");
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.getWriter().println("OK");
        }
    }


    static class ArtifactRepositoryAdapter
            implements ArtifactRepository
    {

        private final RemoteRepository repository;

        public ArtifactRepositoryAdapter( RemoteRepository repository )
        {
            this.repository = repository;
        }

        public String pathOf( org.apache.maven.artifact.Artifact artifact )
        {
            return null;
        }

        public String pathOfRemoteRepositoryMetadata( ArtifactMetadata artifactMetadata )
        {
            return null;
        }

        public String pathOfLocalRepositoryMetadata( ArtifactMetadata metadata, ArtifactRepository repository )
        {
            return null;
        }

        public String getUrl()
        {
            return repository.getUrl();
        }

        public void setUrl( String url )
        {
        }

        public String getBasedir()
        {
            return null;
        }

        public String getProtocol()
        {
            return repository.getProtocol();
        }

        public String getId()
        {
            return repository.getId();
        }

        public void setId( String id )
        {
        }

        public ArtifactRepositoryPolicy getSnapshots()
        {
            return new ArtifactRepositoryPolicy(true, "never", "warn");
        }

        public void setSnapshotUpdatePolicy( ArtifactRepositoryPolicy policy )
        {
        }

        public ArtifactRepositoryPolicy getReleases()
        {
            return  new ArtifactRepositoryPolicy(true, "never", "warn");
        }

        public void setReleaseUpdatePolicy( ArtifactRepositoryPolicy policy )
        {
        }

        public ArtifactRepositoryLayout getLayout()
        {
            return new DefaultRepositoryLayout();
        }

        public void setLayout( ArtifactRepositoryLayout layout )
        {
        }

        public String getKey()
        {
            return getId();
        }

        public boolean isUniqueVersion()
        {
            return true;
        }

        public boolean isBlacklisted()
        {
            return false;
        }

        public void setBlacklisted( boolean blackListed )
        {
        }

        public org.apache.maven.artifact.Artifact find( org.apache.maven.artifact.Artifact artifact )
        {
            return null;
        }

        public List<String> findVersions(org.apache.maven.artifact.Artifact artifact )
        {
            return Collections.emptyList();
        }

        public boolean isProjectAware()
        {
            return false;
        }

        public void setAuthentication( Authentication authentication )
        {
        }

        public Authentication getAuthentication()
        {
            return null;
        }

        public void setProxy( Proxy proxy )
        {
        }

        public Proxy getProxy()
        {
            return null;
        }

        public List<ArtifactRepository> getMirroredRepositories()
        {
            return Collections.emptyList();
        }

        public void setMirroredRepositories( List<ArtifactRepository> mirroredRepositories )
        {
        }

    }

}