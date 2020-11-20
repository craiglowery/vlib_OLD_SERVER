package com.craiglowery.java.vlib.clients.server.job;

import com.craiglowery.java.common.Util;
import com.craiglowery.java.jobmgr.ExecuteUponReturn;
import com.craiglowery.java.jobmgr.Job;
import com.craiglowery.java.jobmgr.JobManager;
import com.craiglowery.java.vlib.clients.core.NameValuePair;
import com.craiglowery.java.vlib.clients.core.NameValuePairList;
import com.craiglowery.java.vlib.clients.core.Tag;
import com.craiglowery.java.vlib.clients.upload.UploadResult;
import com.sun.glass.ui.Application;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class test {


    public static void main(String[] args) {
        try {
            new test("F:\\VPS\\South Park");
        } catch (Exception e) {
            e.printStackTrace();
            Application.GetApplication().terminate();
        }
    }

    public test(String pathToDirectory) throws Exception {



        try {
            File directory = Path.of(pathToDirectory).toFile();
            if (!directory.exists())
                throw new Exception("Directory " + pathToDirectory + " does not exist");

            File logDirectory = directory.toPath().resolve("upload_logs").toFile();
            if (!logDirectory.exists())
                Files.createDirectory(logDirectory.toPath());

            VlibServerJob.setServer(new VlibServer(VlibServerProfile.getProfile("Local Video")));

            LinkedBlockingQueue<VlibServerJob> resultQueue = new LinkedBlockingQueue<>();

            try (PrintStream jobManagerLogStream = new PrintStream(logDirectory.toPath().resolve("jobserver.txt").toFile())) {
                JobManager<VlibServerJobResult, VlibServerJobStatus> jm = new JobManager<>(
                        (job) -> {
                        },
                        jobManagerLogStream
                );


                jm.setDefaultExecuteUponReturn(new ExecuteUponReturn() {
                    @Override
                    public void acceptCompletedJob(Job job) {
                        try {
                            resultQueue.put((VlibServerJob) job);
                        } catch (InterruptedException e) {
                            System.err.println("Blocking on queue was interrupted");
                        }
                    }
                });

                //jm.setRunDepth(1);


                File metaFile = directory.toPath().resolve("meta.txt").toFile();
                if (!metaFile.exists())
                    throw new Exception("No meta.txt file found in " + pathToDirectory);

                class CraigError extends Error {
                    public CraigError(String message) {
                        super(message);
                    }
                }

                Pattern metaPattern = Pattern.compile("^([^=]+)=([^=]+)$");

                NameValuePairList nvps = new NameValuePairList();
                try (java.io.BufferedReader reader = new BufferedReader(new FileReader(metaFile))) {
                    //The first line should be the sentinel
                    String meta = reader.readLine().trim();
                    if (!meta.equals("[meta]"))
                        throw new Exception("Meta file in " + pathToDirectory + " first line must be '[meta]'");
                    //subsequent lines are NameValuePairs
                    final Integer[] lineNo = new Integer[]{1};
                    reader.lines().forEach(line -> {
                        lineNo[0]++;
                        if (!line.trim().equals("")) {  //Skip any empty lines
                            Matcher matcher = metaPattern.matcher(line);
                            if (!matcher.matches())
                                throw new CraigError(String.format("%s line %d: Not a well-formed name=value pair", metaFile.getAbsolutePath(), lineNo[0]));
                            //TODO: Validate name=value here
                            nvps.add(new NameValuePair(matcher.group(1), matcher.group(2)));
                        }
                    });
                } catch (CraigError e) {
                    throw new Exception(e.getMessage());
                }

                //The Series tag is required
                if (!nvps.isNameDefined("Series"))
                    throw new Exception(String.format("File %s: value for name 'Series' is required", metaFile.getName()));

                Pattern fileNamePattern = Pattern.compile("^S(\\d\\d)E(\\d\\d)\\s*\\-\\s*(.+)$");

                for (File file : directory.listFiles()) {

                    String name = file.getName();
                    if (!name.endsWith(".mp4") && !name.endsWith(".mkv") && !name.endsWith(".avi")) {
                        System.out.println("NOT A VIDEO: Skipping file " + name);
                        continue;
                    }

                    Matcher matcher = fileNamePattern.matcher(name);


                    String fileName = file.getAbsolutePath().toString();

                    if (!matcher.matches()) {
                        throw new Exception("Pattern match failed for '" + fileName + "'");
                    }
                    nvps.deleteName("Season");
                    nvps.deleteName("Episode");

                    nvps.add(new NameValuePair("Season", "" + Integer.parseInt(matcher.group(1))));
                    nvps.add(new NameValuePair("Episode", "" + Integer.parseInt(matcher.group(2))));
                    String title = Util.removeExtension(matcher.group(3)).replace("--", ":");

                    System.out.println("QUEUEING: " + fileName);

                    jm.introduce(new VlibServerJob_UploadObject(

                            fileName,
                            title,
                            Files.probeContentType(new File(fileName).toPath()),
                            null,
                            true,
                            (NameValuePairList) nvps.clone(),
                            true
                    ));


                }

                jm.setExitCondition(JobManager.ExitConditions.EXIT_WHEN_IDLE);


                //jm.stopQueue(this);

                Pattern titlePattern = Pattern.compile("^Upload file (.*) \\(.*$");
                while (jm.getQueueStatus() != JobManager.QueueStatus.EXITED || resultQueue.peek() != null) {
                    try {
                        VlibServerJob vlibJob = resultQueue.poll(3, TimeUnit.SECONDS);
                        if (vlibJob == null)
                            continue;
                        Object object = vlibJob.getDataResult();
                        Matcher titleMatcher = titlePattern.matcher(vlibJob.getDescription());
                        String title = "No title discernable";
                        if (titleMatcher.matches())
                            title = titleMatcher.group(1);
                        System.out.println("Job " + vlibJob.getDescription() + " caught in main");
                        try (PrintStream log = new PrintStream(logDirectory.toPath().resolve("" + vlibJob.getId() + " - " + title + ".txt").toFile())) {
                            log.println(vlibJob.toString());
                            switch (vlibJob.getState()) {
                                //------------------------------------------------------------
                                case TERMINATED:
                                    switch (vlibJob.getClass().getSimpleName()) {
                                        case "VlibServerJob_GetTagNames": {
                                            List<Tag> data = (List<Tag>) object;
                                            log.println(data.toString());
                                            break;
                                        }
                                        case "VlibServerJob_GetTagValues": {
                                            List<String> data = (List<String>) object;
                                            log.println(data.toString());
                                            break;
                                        }
                                        case "VlibServerJob_UploadObject": {
                                            UploadResult result = (UploadResult) object;
                                            log.println(vlibJob.getLog());

                                            break;
                                        }
                                        default:
                                            log.println("Unknown job type " + vlibJob.getClass().getSimpleName());
                                    }
                                    System.out.println(String.format("SUCCESS: Job #%d - %s", vlibJob.getId(), vlibJob.getDescription()));
                                    break;
                                //------------------------------------------------------------
                                case ERROR:
                                    log.println("-----------LOG----------------");
                                    log.println(vlibJob.getLog());
                                    if (vlibJob.getError() != null) {
                                        log.println("----------ERROR-----------");
                                        log.println(vlibJob.getError().getMessage());
                                        if (vlibJob.getError().getMessage().contains("ERROR #10: Duplicate content"))
                                            System.out.println(String.format("DUPLICATE: Job #%d - %s", vlibJob.getId(), vlibJob.getDescription()));
                                        else
                                            System.out.println(String.format("UNEXPECTED ERROR: Job #%d - %s", vlibJob.getId(), vlibJob.getDescription()));
                                    } else
                                        System.out.println(String.format("ERROR WITH NO STACKTRACE: Job #%d - %s", vlibJob.getId(), vlibJob.getDescription()));
                                    break;
                                default:
                                    log.println(vlibJob.getLog());
                                    break;

                            }
                        } //Try printstream


                    } catch (InterruptedException e) {

                    }
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


}