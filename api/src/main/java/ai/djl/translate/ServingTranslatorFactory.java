/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.translate;

import ai.djl.Application;
import ai.djl.Model;
import ai.djl.modality.Classifications;
import ai.djl.modality.Input;
import ai.djl.modality.Output;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.CenterCrop;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.modality.cv.translator.SingleShotDetectionTranslator;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.util.JsonUtils;
import ai.djl.util.PairList;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A {@link TranslatorFactory} that creates an generic {@link Translator}. */
public class ServingTranslatorFactory implements TranslatorFactory<Input, Output> {

    private static final Logger logger = LoggerFactory.getLogger(ServingTranslatorFactory.class);

    /** {@inheritDoc} */
    @Override
    public Translator<Input, Output> newInstance(Model model, Map<String, Object> arguments)
            throws TranslateException {
        Path modelDir = model.getModelPath();
        String className = null;
        Path manifestFile = modelDir.resolve("serving.properties");
        if (Files.isRegularFile(manifestFile)) {
            Properties prop = new Properties();
            try (Reader reader = Files.newBufferedReader(manifestFile)) {
                prop.load(reader);
            } catch (IOException e) {
                throw new TranslateException("Failed to load serving.properties file", e);
            }
            for (String key : prop.stringPropertyNames()) {
                arguments.putIfAbsent(key, prop.getProperty(key));
            }
            className = prop.getProperty("translator");
        }

        Path libPath = modelDir.resolve("libs");
        if (!Files.isDirectory(libPath)) {
            libPath = modelDir.resolve("lib");
            if (!Files.isDirectory(libPath)) {
                return loadDefaultTranslator(arguments);
            }
        }
        ServingTranslator translator = findTranslator(libPath, className);
        if (translator != null) {
            translator.setArguments(arguments);
            return translator;
        }
        return loadDefaultTranslator(arguments);
    }

    private ServingTranslator findTranslator(Path path, String className) {
        try {
            Path classesDir = path.resolve("classes");
            compileJavaClass(classesDir);

            List<Path> jarFiles =
                    Files.list(path)
                            .filter(p -> p.toString().endsWith(".jar"))
                            .collect(Collectors.toList());
            List<URL> urls = new ArrayList<>(jarFiles.size() + 1);
            urls.add(classesDir.toUri().toURL());
            for (Path p : jarFiles) {
                urls.add(p.toUri().toURL());
            }

            ClassLoader parentCl = Thread.currentThread().getContextClassLoader();
            ClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), parentCl);
            if (className != null && !className.isEmpty()) {
                return initTranslator(cl, className);
            }

            ServingTranslator translator = scanDirectory(cl, classesDir);
            if (translator != null) {
                return translator;
            }

            for (Path p : jarFiles) {
                translator = scanJarFile(cl, p);
                if (translator != null) {
                    return translator;
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to find Translator", e);
        }
        return null;
    }

    private ServingTranslator scanDirectory(ClassLoader cl, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            logger.debug("Directory not exists: {}", dir);
            return null;
        }
        Collection<Path> files =
                Files.walk(dir)
                        .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class"))
                        .collect(Collectors.toList());
        for (Path file : files) {
            Path p = dir.relativize(file);
            String className = p.toString();
            className = className.substring(0, className.lastIndexOf('.'));
            className = className.replace(File.separatorChar, '.');
            ServingTranslator translator = initTranslator(cl, className);
            if (translator != null) {
                return translator;
            }
        }
        return null;
    }

    private ServingTranslator scanJarFile(ClassLoader cl, Path path) throws IOException {
        try (JarFile jarFile = new JarFile(path.toFile())) {
            Enumeration<JarEntry> en = jarFile.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                String fileName = entry.getName();
                if (fileName.endsWith(".class")) {
                    fileName = fileName.substring(0, fileName.lastIndexOf('.'));
                    fileName = fileName.replace('/', '.');
                    ServingTranslator translator = initTranslator(cl, fileName);
                    if (translator != null) {
                        return translator;
                    }
                }
            }
        }
        return null;
    }

    private ServingTranslator initTranslator(ClassLoader cl, String className) {
        try {
            Class<?> clazz = Class.forName(className, true, cl);
            Class<? extends ServingTranslator> subclass = clazz.asSubclass(ServingTranslator.class);
            Constructor<? extends ServingTranslator> constructor = subclass.getConstructor();
            return constructor.newInstance();
        } catch (Throwable e) {
            logger.trace("Not able to load ModelServerTranslator", e);
        }
        return null;
    }

    private Translator<Input, Output> loadDefaultTranslator(Map<String, Object> arguments) {
        String appName = (String) arguments.get("application");
        if (appName != null) {
            Application application = Application.of(appName);
            if (application == Application.CV.IMAGE_CLASSIFICATION) {
                return getImageClassificationTranslator(arguments);
            } else if (application == Application.CV.OBJECT_DETECTION) {
                // TODO: check model name
                return getSsdTranslator(arguments);
            }
        }
        return new RawTranslator();
    }

    private Translator<Input, Output> getImageClassificationTranslator(
            Map<String, Object> arguments) {
        int width = getOrDefault(arguments, "width", 224).intValue();
        int height = getOrDefault(arguments, "height", 224).intValue();
        String flag = arguments.getOrDefault("flag", Image.Flag.COLOR.name()).toString();
        boolean softmax = Boolean.parseBoolean(arguments.getOrDefault("softmax", false).toString());

        Translator<Image, Classifications> translator =
                ImageClassificationTranslator.builder()
                        .optFlag(Image.Flag.valueOf(flag))
                        .addTransform(new CenterCrop())
                        .addTransform(new Resize(width, height))
                        .addTransform(new ToTensor())
                        .optApplySoftmax(softmax)
                        .build();
        return new ImageServingTranslator(translator);
    }

    private Translator<Input, Output> getSsdTranslator(Map<String, Object> arguments) {
        int width = getOrDefault(arguments, "width", 512).intValue();
        int height = getOrDefault(arguments, "height", 512).intValue();
        double threshold = getOrDefault(arguments, "threshold", 0.2d);
        String flag = arguments.getOrDefault("flag", Image.Flag.COLOR.name()).toString();
        String synset = arguments.getOrDefault("synset", "synset.txt").toString();

        SingleShotDetectionTranslator translator =
                SingleShotDetectionTranslator.builder()
                        .optFlag(Image.Flag.valueOf(flag))
                        .addTransform(new Resize(width, height))
                        .addTransform(new ToTensor())
                        .optThreshold((float) threshold)
                        .optRescaleSize(width, height)
                        .optSynsetArtifactName(synset)
                        .build();
        return new ImageServingTranslator(translator);
    }

    private void compileJavaClass(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                logger.debug("Directory not exists: {}", dir);
                return;
            }
            String[] files =
                    Files.walk(dir)
                            .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                            .map(p -> p.toAbsolutePath().toString())
                            .toArray(String[]::new);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (files.length > 0) {
                compiler.run(null, null, null, files);
            }
        } catch (Throwable e) {
            logger.warn("Failed to compile bundled java file", e);
        }
    }

    private static Double getOrDefault(
            Map<String, Object> arguments, String key, double defaultValue) {
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Double.valueOf(value.toString());
    }

    private static final class ImageServingTranslator implements Translator<Input, Output> {

        private Translator<Image, ?> translator;
        private ImageFactory factory;

        public ImageServingTranslator(Translator<Image, ?> translator) {
            this.translator = translator;
            factory = ImageFactory.getInstance();
        }

        /** {@inheritDoc} */
        @Override
        public Batchifier getBatchifier() {
            return translator.getBatchifier();
        }

        /** {@inheritDoc} */
        @Override
        public Output processOutput(TranslatorContext ctx, NDList list) throws Exception {
            Input input = (Input) ctx.getAttachment("input");
            Output output = new Output(input.getRequestId(), 200, "OK");
            Object obj = translator.processOutput(ctx, list);
            output.setContent(JsonUtils.GSON_PRETTY.toJson(obj) + '\n');
            return output;
        }

        /** {@inheritDoc} */
        @Override
        public NDList processInput(TranslatorContext ctx, Input input) throws Exception {
            ctx.setAttachment("input", input);
            PairList<String, byte[]> inputs = input.getContent();
            byte[] data = inputs.get("data");
            if (data == null) {
                data = inputs.get("body");
            }
            if (data == null) {
                data = input.getContent().valueAt(0);
            }
            Image image = factory.fromInputStream(new ByteArrayInputStream(data));
            return translator.processInput(ctx, image);
        }

        /** {@inheritDoc} */
        @Override
        public void prepare(NDManager manager, Model model) throws IOException {
            translator.prepare(manager, model);
        }
    }

    private static final class RawTranslator implements Translator<Input, Output> {

        /** {@inheritDoc} */
        @Override
        public Batchifier getBatchifier() {
            return null;
        }

        /** {@inheritDoc} */
        @Override
        public NDList processInput(TranslatorContext ctx, Input input) throws IOException {
            ctx.setAttachment("input", input);
            PairList<String, byte[]> inputs = input.getContent();
            byte[] data = inputs.get("data");
            if (data == null) {
                data = inputs.get("body");
            }
            if (data == null) {
                data = input.getContent().valueAt(0);
            }
            NDManager manager = ctx.getNDManager();
            return NDList.decode(manager, data);
        }

        /** {@inheritDoc} */
        @Override
        public Output processOutput(TranslatorContext ctx, NDList list) {
            Input input = (Input) ctx.getAttachment("input");
            Output output = new Output(input.getRequestId(), 200, "OK");
            output.setContent(list.encode());
            return output;
        }
    }
}
