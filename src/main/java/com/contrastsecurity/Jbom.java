package com.contrastsecurity;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.LineIterator;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.stream.LogOutputStream;

import net.bytebuddy.agent.ByteBuddyAgent;
import picocli.CommandLine;

public class Jbom implements Runnable {
    
    @CommandLine.Option(names = { "-h", "--host" }, description = "Hostname or IP address to connect to")
    private String host = null;

    @CommandLine.Option(names = { "-U", "--user" }, description = "Username of user to connect as")
    private String user;

    @CommandLine.Option(names = { "-P", "--password" }, description = "Password for user" )
    private String pass;

    @CommandLine.Option(names = { "-r", "--remote" }, defaultValue = "/tmp/jbom", description = "Remote directory to use" )
    private String remoteDir = "/tmp/jbom";

    @CommandLine.Option(names = { "-p", "--pid" }, defaultValue = "all", description = "Java process pid to attach to or 'all'" )
    private String pid = "all";

    @CommandLine.Option(names = { "-x", "--exclude" }, description = "Java process pid to exclude" )
    private String exclude;

    @CommandLine.Option(names = { "-f", "--file" }, description = "File to be scanned" )
    private String file;

    @CommandLine.Option(names = { "-d", "--dir" }, description = "Directory to be scanned" )
    private String dir;

    @CommandLine.Option(names = { "-o", "--outputDir" }, description = "Output directory" )
    private String outputDir = System.getProperty("user.dir") + "/jbom";

    @CommandLine.Option(names = { "-t", "--tag" }, description = "Tag to use in output filenames" )
    private String tag;

    @CommandLine.Option(names = { "-D", "--debug" }, description = "Enable debug output" )
    private boolean debug = false;

    @CommandLine.Option(names = { "-l", "--list" }, description = "List Java processes" )
    private boolean list = false;

    
    public static void main(String[] args){
        int exitCode = new CommandLine(new Jbom()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {

        Jbom jbom = new Jbom();
        jbom.printBanner();
        Logger.setDebug( debug );
		Logger.debug( "Java vendor : " + System.getProperty( "java.vendor" ) );
		Logger.debug( "Java version: " + System.getProperty( "java.version" ) );

        // remote
        if ( host != null ) {

            if ( list ) {
                jbom.doRemoteList( exclude, host, user, pass );
            }

            else if ( dir != null ) {
                jbom.doRemoteDirectory( dir, outputDir, host, user, pass, remoteDir );
            }

            else {
                jbom.doRemoteProcess( pid, exclude, outputDir, host, user, pass, remoteDir );
            }
        }

        // local
        else {
            if ( list ) {
                jbom.doLocalList( exclude );
            }
        
           else if ( file != null ) {
                jbom.doLocalFile( file, outputDir );
            }

            else if ( dir != null ) {
                jbom.doLocalDirectory( dir, outputDir );
            }
        
            else {
                jbom.doLocalProcess( pid, exclude, outputDir, tag );
            }
        }

        Logger.log( "" );
        Logger.log( "jbom complete" );
    }


    public void doLocalList( String exclude ) {
        try {
            Map<String, String> processes = getJavaProcesses( exclude );
            Logger.log( "Detected " + processes.size() + " local Java process" + ( processes.size()==1 ? "" : "es" ) );
            for ( String procid : processes.keySet() ) {
                Logger.log( "  " + procid + " (" + processes.get( procid ) + ")" );
            }
        } catch (Exception e ) {
            Logger.log( "Error. Could not list local processes" );
            e.printStackTrace();
        }
    }

    public void doLocalProcess(String pid, String exclude, String outputDir, String tag) {
        if ( pid.equals( "all" ) ) {
            try {
                Map<String, String> processes = getJavaProcesses( exclude );
                Logger.log( "Detected "+processes.size()+" local Java process" + ( processes.size()==1 ? "" : "es" ) );
                int count = 1;
                for ( String procid : processes.keySet() ) {
                    Logger.log( "  " + procid + " (" + processes.get( procid ) + ")" );
                }
            
                Logger.log( "" );
                Logger.log( "Starting analysis..." );
                count = 1;
                for( String procid : processes.keySet() ) {
                    Logger.log( "" );
                    Logger.log( "  " + count++ + ": " + procid + " (" + processes.get( procid ) + ")" );
                    String name = outputDir + "/" + ( tag == null ? "" : "-" +tag ) + "-" + procid + "-cyclonedx.json";
                    attach( procid, name );
                }
            } catch( Exception e ) {
                e.printStackTrace();
            }
        } else {
            Logger.log( "Analyzing local Java process with pid " + pid );
            String name = outputDir + "/" + ( tag == null ? "" : "-" +tag ) + "-" + pid + "-cyclonedx.json";
            attach( pid, name);
        }
    }
    public Libraries doLocalFile(String file, String outputDir) {
        File f = new File( file );
        return doLocalFile(f,outputDir);
    }


    public Libraries doLocalFile(File file, String outputDir) {
        Logger.log( "Analyzing file " + file );
        Libraries libs = new Libraries();
        if ( !file.exists() ) {
            Logger.log( "Could not find file: " + file );
        }
        if ( !file.isFile() ) {
            Logger.log( "Could not open file: " + file );
        }
        if ( !libs.isArchive( file.getAbsolutePath() ) ) {
            Logger.log( "File does not appear to be an archive: " + file );
        }

        try{
            String name = file.getName();
            int idx = name.lastIndexOf('/');
            if ( idx != -1 ) {
                name = name.substring( idx + 1 );
            }
            idx = name.lastIndexOf('.');
            if ( idx != -1 ) {
                name = name.substring( 0, idx );
            }
            name = outputDir + "/" + name + ( tag == null ? "" : "-" +tag ) + "-cyclonedx.json";
            libs.runScan( file );
            libs.save(name);
        }catch(Exception e){
            e.printStackTrace();
        }
        return libs;
    }

    public Libraries doLocalDirectory(String dir, String outputDir) {
        Logger.log( "Analyzing local directory " + dir );
        Libraries libs = new Libraries();
        Path path = Paths.get( dir );
        String dirname = path.getFileName().toString();

        try {
            String name = outputDir + "/" + dirname + ( tag == null ? "" : "-" +tag ) + "-cyclonedx.json";
            List<Path> paths = listFiles(path);
            for ( Path p : paths ) {
                try {
                    libs.runScan( p.toFile() );
                } catch( Exception e ) {
                    Logger.log( "  Problem processing local file " + p + ". Continuing...");
                }
            }
            libs.save(name);
        } catch( Exception e ) {
            Logger.log( "  Couldn't get a list of files in " + dir );
        }
        return libs;
    }

    public void doRemoteDirectory(String dir, String outputDir, String host, String user, String pass, String remoteDir ) {
        Logger.log( "Analyzing remote directory " + dir );
        java.io.Console console = System.console();
        if ( user == null ) {
            console.readLine("Username: ");
        }
        if ( pass == null ) {
            pass = new String(console.readPassword("Password: "));
        }

        try {
            Remote remote = new Remote( host, user, pass );

            // 1. upload the jbom.jar file
            String filename = Jbom.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            File agentFile = new File(filename);
            remote.upload( remoteDir, agentFile );
            
            // 2. run java -jar jbom.jar on remote server
            Logger.log( "Connecting to " + host );
            remote.exec( "java -jar " + agentFile.getAbsolutePath() + " -d " + dir + " -o " + remoteDir + ( debug ? " -D" : "" ) + " -t " + host );

            // 3. download results and cleanup
            File odir = new File( outputDir );
            if ( !odir.exists() || !odir.isDirectory() ) {
                odir.mkdirs();
            }
            List<String> files = remote.download( host, remoteDir, outputDir );
            for ( String file : files ) {
                Logger.log( "  - " + file );
            }
            Logger.log( "Remote Java directory analysis complete" );
            Logger.log( "Saving SBOMs for " + host + " to directory: " + outputDir );

        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    public void doRemoteList( String exclude, String host, String user, String pass ) {
        Logger.log( "Listing remote JVMs on " + host );
        java.io.Console console = System.console();
        if ( user == null ) {
            console.readLine("Username: ");
        }
        if ( pass == null ) {
            pass = new String(console.readPassword("Password: "));
        }

        try {
            Remote remote = new Remote( host, user, pass );
 
            Map<String, String> processes = new TreeMap<String,String>();
            String results = remote.exec( "jcmd -l" );

            LineIterator li = new LineIterator( new StringReader( results ) );
            while ( li.hasNext() ) {
                String line = li.nextLine();
                int idx = line.indexOf( " " );
                String key = line.substring(0, idx);
                String value = line.substring( idx + 1 );
                if (
                    (exclude != null && value.contains( exclude )) ||
                    value.contains( "jbom" ) || 
                    value.contains( ".vscode" ) ||
                    value.contains( "JCmd" )
                ) {
                    Logger.debug( "Skipping process: " + key + " --> " + value );
                } else {
                    Logger.debug( "Adding process: " + key + " --> " + value );
                    processes.put( key, value );
                }
            }

            Logger.log( "Detected " + processes.size() + " remote Java process" + ( processes.size()==1 ? "" : "es" ) );
            for ( String procid : processes.keySet() ) {
                Logger.log( "  " + procid + " (" + processes.get( procid ) + ")" );
            }

        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    public void doRemoteProcess(String pid, String exclude, String outputDir, String host, String user, String pass, String remoteDir) {
        Logger.log( "Analyzing remote JVMs on " + host );
        java.io.Console console = System.console();
        if ( user == null ) {
            console.readLine("Username: ");
        }
        if ( pass == null ) {
            pass = new String(console.readPassword("Password: "));
        }

        try {
            Remote remote = new Remote( host, user, pass );

            // 1. upload the jbom.jar file
            String filename = Remote.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            File agentFile = new File(filename);
            remote.upload( remoteDir, agentFile );
            
            // 2. run java -jar jbom.jar on remote server
            Logger.log( "Connecting to " + host );
            String myPid = ByteBuddyAgent.ProcessProvider.ForCurrentVm.INSTANCE.resolve();
            remote.exec( "java -jar " + agentFile.getAbsolutePath() + " -x " + myPid + " -o " + remoteDir + ( debug ? " -D" : "" ) + " -t " + host );

            // 3. download results and cleanup
            File odir = new File( outputDir );
            if ( !odir.exists() || !odir.isDirectory() ) {
                odir.mkdirs();
            }
            List<String> files = remote.download( host, remoteDir, outputDir );
            Logger.log( "Generating " + files.size() + " remote SBOM" + ( files.size() != 1 ? "s" : "" ) );
            for ( String file : files ) {
                Logger.log( "  - " + file );
            }
            Logger.log( "Remote Java process analysis complete" );
            Logger.log( "Saving SBOMs for " + host + " to directory: " + outputDir );
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }


    // public Map<String, String> getJavaProcesses( String exclude ) throws Exception {
    //     Map<String,String> map = new HashMap<String, String>();
    //     List<VirtualMachineDescriptor> vms = VirtualMachine.list();
    //     vms.stream()
    //         .filter( vm -> 
    //             !vm.id().equals( exclude ) &&
    //             !vm.displayName().contains("jbom") &&
    //             !vm.displayName().contains(".vscode")
    //             )
    //         .forEach(vm -> {
    //             map.put(vm.id(), vm.displayName());
    //         });
    //     return map;
    // }

    public Map<String, String> getJavaProcesses( String exclude ) throws Exception {
        Map<String, String> map = new TreeMap<String,String>();
        new ProcessExecutor().command("jcmd", "-l")
        .redirectOutput(new LogOutputStream() {
            @Override
            protected void processLine(String line) {
                int idx = line.indexOf( " " );
                String key = line.substring(0, idx);
                String value = line.substring( idx + 1 );
                if (
                    (exclude != null && value.contains( exclude )) ||
                    value.contains( "jbom" ) || 
                    value.contains( ".vscode" ) ||
                    value.contains( "JCmd" )
                ) {
                    Logger.debug( "Skipping process: " + key + " --> " + value );
                } else {
                    Logger.debug( "Adding process: " + key + " --> " + value );
                    map.put( key, value );
                }
            }
        })
        .execute();
        return map;
    }

  public void attach( String pid, String path) {

        String myPid = ByteBuddyAgent.ProcessProvider.ForCurrentVm.INSTANCE.resolve();
        if ( pid.equals( myPid ) ) {
            Logger.log( "     Skipping jbom process (pid: "+myPid+")" );
            return;
        }

        if (pid != null && !pid.isEmpty() ) {
            try{
                Logger.log( "     Analyzing..." );
                String filename = Jbom.class.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI()
                        .getPath();
                File agentFile = new File(filename).getAbsoluteFile();
                attachWithFallback( agentFile, pid, path );
                // ByteBuddyAgent.attach(agentFile, pid, path);
            } catch(Exception e) {
                Logger.debug( "     Error attaching to " + pid );
                Logger.debug ( "      --> " + e.getMessage() );
                //e.printStackTrace();
            }
        }
        Logger.log( "     Saving SBOM to " + path );
    }

    private static void attachWithFallback(File agentJarFile, String pid, String agentArgs) {
        try {
            ByteBuddyAgent.attach(agentJarFile, pid, agentArgs, JbomAttachmentProvider.get());
        } catch (RuntimeException e1) {
            System.err.println("Unable to attach with regular provider:");
            e1.printStackTrace();

            try {
                ByteBuddyAgent.attach(agentJarFile, pid, agentArgs, JbomAttachmentProvider.getFallback());
            } catch (RuntimeException e2) {
                System.err.println("Unable to attach with fallback provider:");
                e2.printStackTrace();
            }
        }
    }

    // list all files from this path
    public static List<Path> listFiles(Path path) throws IOException {
        List<Path> result;
        try (Stream<Path> walk = Files.walk(path)) {
            result = walk.filter(Files::isRegularFile)
                    .collect(Collectors.toList());
        }
        return result;
    }

    private void printBanner() {
        Logger.log( "" );
        Logger.log( "                      _ __" );
        Logger.log( "                     (_) /_  ____  ____ ___" );
        Logger.log( "                    / / __ \\/ __ \\/ __ `__ \\" );
        Logger.log( "                   / / /_/ / /_/ / / / / / /" );
        Logger.log( "                __/ /_.___/\\____/_/ /_/ /_/" );
        Logger.log( "               /___/" );
        Logger.log( "" );
        Logger.log( "     by Contrast Security - https://contrastsecurity.com" );
        Logger.log( "" );
        Logger.log( "      jbom generates SBOMs for all JVMs running on a host" );
        Logger.log( "         https://projects.eclipse.org/projects/technology.jbom" );
        Logger.log( "" );
    }

}
