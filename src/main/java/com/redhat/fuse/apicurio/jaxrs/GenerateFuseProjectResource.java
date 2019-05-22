/*
 * Copyright (C) 2018 Red Hat, Inc.
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
package com.redhat.fuse.apicurio.jaxrs;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;
import io.swagger.annotations.Api;
import io.swagger.util.Json;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.jboss.shrinkwrap.api.GenericArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.ClassLoaderAsset;
import org.jboss.shrinkwrap.api.asset.FileAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/**
 * Implements a jaxrs resource that can be used by Apicurito to
 * generate Camel projects from an openapi spec.
 */
@Path("/api/v1/generate")
@Api(value = "generate")
@Component
public class GenerateFuseProjectResource {

    private static Logger logger = LoggerFactory.getLogger(GenerateFuseProjectResource.class);

    /**
     * Generate an example zip file
     */
    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Path(value = "/nodejs-express-project.zip")
    public InputStream generateNodeJsExpressOnlyTemplate() throws Exception {
        GenericArchive archive = ShrinkWrap.create(GenericArchive.class, "nodejs-express-project.zip");
        addRecursiveResourceDirectory(archive, "nodejs-express-project-template");
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Path(value = "/nodejs-express-project.zip")
    public InputStream generateNodeJsExpressWithSpec(String openApiSpec) throws Exception {
        java.nio.file.Path tempDir = Files.createTempDirectory("codegen");
        try {
            GenericArchive archive = ShrinkWrap.create(GenericArchive.class, "nodejs-express-project.zip");
            if (specIsValidJson(openApiSpec)) {
                archive.add(new StringAsset(openApiSpec), "openapi.json");
            } else {
                archive.add(new StringAsset(openApiSpec), "openapi.yml");
            }
            runCodeGeneration(tempDir, archive, openApiSpec);
            return archive.as(ZipExporter.class).exportAsInputStream();
        } finally {
            tempDir.toFile().deleteOnExit();
        }
    }

    boolean runCodeGeneration(java.nio.file.Path tempDir, GenericArchive archive, String openApiSpec) throws Exception {
        String templatePath = "/rhnodejs-template";
        File template = new File(templatePath);
        if (! template.exists()) {
            logger.warn("Template directory (" + templatePath + ") does not exist");
            return false;
        }

        File specFile = new File(tempDir.toFile(), "spec");
        Files.write(specFile.toPath(), openApiSpec.getBytes());
        List<String> cmdArray = Arrays.asList("/usr/bin/snc",
                "-o", tempDir.toFile().getAbsolutePath(),
                "-t", templatePath,
                specFile.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(cmdArray);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
        //snc doesn't utilize proper error codes!
        List<String> outputLines = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            outputLines.add(line);
        }
        int exitCode = process.waitFor();
        specFile.delete();
        if (!outputLines.isEmpty()) {
            String firstLine = outputLines.get(0);
            boolean retval = firstLine.contains("Done!");
            if (retval) {
                logger.info("Codegen succeeded");
                addRecursiveFilesystemDirectory(archive, tempDir.toFile());
            } else {
                logger.warn("Codegen failed, exit code was " + exitCode);
                outputLines.forEach(l -> {
                    logger.warn("Codegen output: " + l);
                });
            }
            return retval;
        } else {
            logger.warn("Codegen failed, exit code was " + exitCode);
            outputLines.forEach(l -> {
                logger.warn("Codegen output: " + l);
            });
            return false;
        }
    }

    void addRecursiveFilesystemDirectory(GenericArchive archive, File dir) {
        Collection files = FileUtils.listFiles(dir, new RegexFileFilter("^(.*?)"),
                DirectoryFileFilter.DIRECTORY
        );
        final String dirString = dir.toString() + "/";
        files.forEach(f -> {
            String filename = f.toString();
            String shortName = filename.substring(dirString.length());
            archive.add(new FileAsset((File)f), shortName);
        });
    }

    static boolean specIsValidJson(String openApiSpec) {
        try {
            Json.mapper().readTree(openApiSpec);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    void addRecursiveResourceDirectory(GenericArchive archive, final String dir) throws IOException {
        final Set<String> resourceFilenames = new HashSet<>();
        try (ScanResult scanResult = new ClassGraph().whitelistPaths("/" + dir).scan()) {
            scanResult.getAllResources()
                .forEachByteArray((Resource res, byte[] fileContent) -> {
                    resourceFilenames.add(res.getPath());
                });
        }
        resourceFilenames.forEach(filename -> {
            String shortName = filename.substring(dir.length() + 1);
            archive.add(new ClassLoaderAsset(filename), shortName);
        });
    }

}
