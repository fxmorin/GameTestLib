package ca.fxco.gametestlib.gametest;

import ca.fxco.api.gametestlib.config.ParsedValue;
import ca.fxco.api.gametestlib.gametest.GameTestChanges;
import ca.fxco.gametestlib.GameTestLibMod;
import ca.fxco.gametestlib.Utils.Utils;
import ca.fxco.api.gametestlib.gametest.GameTestLib;
import ca.fxco.gametestlib.gametest.expansion.ParsedGameTestConfig;
import ca.fxco.gametestlib.gametest.expansion.TestFunctionGenerator;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.Pair;
import lombok.*;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

public class TestGenerator {

    private static final String ENTRYPOINT_KEY = GameTestLibMod.MOD_ID + "-gametest";

    private static final Map<Class<?>, String> GAME_TEST_IDS = new HashMap<>();

    public TestGenerator() {
        GameTestLibMod.initialize();
    }

    @GameTestGenerator
    public Collection<TestFunction> generateBatches() {
        List<TestFunction> simpleTestFunctions = new ArrayList<>();
        List<TestFunctionGenerator> testFunctionGenerators = new ArrayList<>();

        for (Class<?> clazz : getEntrypoints()) {
            Pair<List<TestFunction>, List<TestFunctionGenerator>> pair = generateTestFunctions(clazz, clazz.getSimpleName().toLowerCase());
            simpleTestFunctions.addAll(pair.first());
            testFunctionGenerators.addAll(pair.second());
        }

        List<TestGenerator.GameTestCalcBatch> gameTestCalcBatches = checkAllCombinations(testFunctionGenerators);
        int countBatch = 0;
        for (TestGenerator.GameTestCalcBatch calcBatch : gameTestCalcBatches) {
            String batchId = calcBatch.hasName() ? calcBatch.getName() : "" + countBatch;
            // TODO: Add a way to try all combinations of options, instead of one at a time
            if (calcBatch.getValues().size() != 0) {
                for (String configName : calcBatch.getValues()) {
                    Optional<ParsedValue<?>> parsedValueOpt = GameTestLibMod.CONFIG_MANAGER.get(configName);
                    if (parsedValueOpt.isEmpty()) {
                        continue;
                    }
                    generateValueTestFunctions(parsedValueOpt.get(), batchId, configName, calcBatch, simpleTestFunctions);
                }
            } else {
                generateSimpleTestFunctions(batchId, calcBatch, simpleTestFunctions);
            }
            countBatch++;
        }
        System.out.println("TestGenerator has generated: " + simpleTestFunctions.size() + " test functions as: " + countBatch + " batches");

        return simpleTestFunctions;
    }

    private <T> void generateValueTestFunctions(ParsedValue<T> parsedValue, String batchId, String configName,
                                                TestGenerator.GameTestCalcBatch calcBatch,
                                                List<TestFunction> simpleTestFunctions) {
        T[] testingValues = parsedValue.getTestingValues();
        for (int i = 0; i < testingValues.length; i++) {
            String currentBatchId = batchId + "-" + configName + "-" + i;
            int finalI = i;
            Consumer<ServerLevel> beforeBatchConsumer = GameTestRegistry.BEFORE_BATCH_FUNCTIONS.getOrDefault(batchId, null);
            GameTestRegistry.BEFORE_BATCH_FUNCTIONS.put(currentBatchId, serverLevel -> {
                T value = testingValues[finalI];
                parsedValue.setValue(value);
                if (beforeBatchConsumer != null) {
                    beforeBatchConsumer.accept(serverLevel);
                }
            });
            Consumer<ServerLevel> afterBatchConsumer = GameTestRegistry.AFTER_BATCH_FUNCTIONS.getOrDefault(batchId, null);
            GameTestRegistry.AFTER_BATCH_FUNCTIONS.put(currentBatchId, serverLevel -> {
                parsedValue.setDefault();
                if (afterBatchConsumer != null) {
                    afterBatchConsumer.accept(serverLevel);
                }
            });
            for (TestFunctionGenerator generator : calcBatch.testFunctionGenerators) {
                ParsedGameTestConfig gameTestConfig = generator.getGameTestConfig();
                simpleTestFunctions.add(
                        generateTestFunctionWithCustomData(
                                generator.getMethod(),
                                gameTestHelper -> {
                                    if (gameTestConfig.customBlocks()) {
                                        GameTestChanges changes = generator.getSpecialValues()
                                                .getOrDefault(configName, GameTestChanges.NONE);
                                        GameTestUtil.initializeGameTestLib(gameTestHelper, changes);
                                    }
                                    turnMethodIntoConsumer(generator.getMethod()).accept(gameTestHelper);
                                },
                                generator.getGameTestDataBuilder().batch(currentBatchId).build()
                        )
                );
            }
        }
    }

    private <T> void generateSimpleTestFunctions(String batchId, TestGenerator.GameTestCalcBatch calcBatch,
                                                 List<TestFunction> simpleTestFunctions) {
        // Before/After batch already match ID
        for (TestFunctionGenerator generator : calcBatch.testFunctionGenerators) {
            ParsedGameTestConfig gameTestConfig = generator.getGameTestConfig();
            simpleTestFunctions.add(
                    generateTestFunctionWithCustomData(
                            generator.getMethod(),
                            gameTestHelper -> {
                                if (gameTestConfig.customBlocks()) {
                                    GameTestUtil.initializeGameTestLib(gameTestHelper, GameTestChanges.NONE);
                                }
                                turnMethodIntoConsumer(generator.getMethod()).accept(gameTestHelper);
                            },
                            generator.getGameTestDataBuilder().batch(batchId).build()
                    )
            );
        }
    }

    public static List<Class<?>> getEntrypoints() {
        List<EntrypointContainer<Object>> entrypointContainers = FabricLoader.getInstance()
                .getEntrypointContainers(ENTRYPOINT_KEY, Object.class);

        List<Class<?>> entrypointClasses = new ArrayList<>();
        for (EntrypointContainer<Object> container : entrypointContainers) {
            Class<?> testClass = container.getEntrypoint().getClass();
            String modid = container.getProvider().getMetadata().getId();

            if (GAME_TEST_IDS.containsKey(testClass)) {
                throw new UnsupportedOperationException("Test class (%s) has already been registered with mod (%s)".formatted(testClass.getCanonicalName(), modid));
            }
            GAME_TEST_IDS.put(testClass, modid);

            entrypointClasses.add(testClass);
        }
        return entrypointClasses;
    }

    public static List<TestGenerator.GameTestCalcBatch> checkAllCombinations(List<TestFunctionGenerator> testFunctionGenerators) {
        List<GameTestCalcBatch> gameTestCalcBatches = new ArrayList<>();
        for (TestFunctionGenerator generator : testFunctionGenerators) {
            ParsedGameTestConfig gameTestConfig = generator.getGameTestConfig();
            boolean gotBatch = false;
            if (gameTestConfig.ignored()) {
                if (gameTestConfig.combined()) {
                    for (GameTestCalcBatch batch : new ArrayList<>(gameTestCalcBatches)) {
                        if (Utils.containsAny(generator.getValues(), batch.getValues()) &&
                                batch.canAcceptGenerator(generator)) {
                            batch.addGenerator(generator);
                            gotBatch = true;
                            break;
                        }
                    }
                } else {
                    for (GameTestCalcBatch batch : new ArrayList<>(gameTestCalcBatches)) {
                        if (generator.getValues().containsAll(batch.getValues()) &&
                                batch.canAcceptGenerator(generator)) {
                            batch.addGenerator(generator);
                            gotBatch = true;
                            break;
                        }
                    }
                }
            } else {
                if (gameTestConfig.combined()) {
                    for (GameTestCalcBatch batch : new ArrayList<>(gameTestCalcBatches)) {
                        List<String> differences = new ArrayList<>(Sets.difference(Sets.newHashSet(batch.getValues()), Sets.newHashSet(generator.getValues())));
                        if (differences.size() == 0 && batch.canAcceptGenerator(generator)) {
                            batch.addGenerator(generator);
                            gotBatch = true;
                            break;
                        }
                    }
                } else {
                    for (GameTestCalcBatch batch : new ArrayList<>(gameTestCalcBatches)) {
                        for (String str : generator.getValues()) {
                            if (batch.getValues().contains(str)) {
                                if (batch.canAcceptGenerator(generator)) {
                                    batch.addGenerator(generator);
                                    gotBatch = true;
                                }
                                break;
                            }
                        }
                        List<String> differences = new ArrayList<>(Sets.difference(Sets.newHashSet(batch.getValues()), Sets.newHashSet(generator.getValues())));
                        if (differences.size() == 0 && batch.canAcceptGenerator(generator)) {
                            batch.addGenerator(generator);
                            gotBatch = true;
                            break;
                        }
                    }
                }
            }
            if (!gotBatch) {
                GameTestCalcBatch newBatch = new GameTestCalcBatch();
                newBatch.addGenerator(generator);
                gameTestCalcBatches.add(newBatch);
            }
        }
        return gameTestCalcBatches;
    }

    public static Pair<List<TestFunction>, List<TestFunctionGenerator>> generateTestFunctions(Class<?> clazz, String batch) {
        List<TestFunction> simpleTestFunctions = new ArrayList<>();
        List<TestFunctionGenerator> testFunctionGenerators = new ArrayList<>();
        ParsedGameTestConfig classConfig = clazz.isAnnotationPresent(GameTestLib.class) ?
                ParsedGameTestConfig.of(clazz.getAnnotation(GameTestLib.class)) : null;
        Arrays.stream(clazz.getDeclaredMethods()).forEach(m -> {
            if (m.isAnnotationPresent(GameTest.class)) {
                GameTest gameTest = m.getAnnotation(GameTest.class);
                GameTestData.GameTestDataBuilder gameTestDataBuilder = GameTestData.builderFrom(gameTest);
                if (m.isAnnotationPresent(GameTestLib.class)) {
                    ParsedGameTestConfig gameTestConfig;
                    if (classConfig != null) {
                        gameTestConfig = classConfig.createMerged(m.getAnnotation(GameTestLib.class), true);
                    } else {
                        gameTestConfig = ParsedGameTestConfig.of(m.getAnnotation(GameTestLib.class));
                    }
                    testFunctionGenerators.add(new TestFunctionGenerator(m, gameTestConfig, gameTestDataBuilder.batch(batch)));
                } else {
                    if (classConfig != null) {
                        testFunctionGenerators.add(new TestFunctionGenerator(m, classConfig, gameTestDataBuilder.batch(batch)));
                    } else {
                        // If no GameTestConfig is available, only run it once with default config options. Part of the `simple` batch
                        simpleTestFunctions.add(generateTestFunctionWithCustomData(m, gameTestDataBuilder.batch("simple").build()));
                    }
                }
            }
            registerBatchFunction(m, BeforeBatch.class, BeforeBatch::batch, GameTestRegistry.BEFORE_BATCH_FUNCTIONS);
            registerBatchFunction(m, AfterBatch.class, AfterBatch::batch, GameTestRegistry.AFTER_BATCH_FUNCTIONS);

            GameTestGenerator gameTestGenerator = m.getAnnotation(GameTestGenerator.class);
            if (gameTestGenerator != null) {
                simpleTestFunctions.addAll(useTestGeneratorMethod(m));
            }
        });
        return Pair.of(simpleTestFunctions, testFunctionGenerators);
    }

    private static TestFunction generateTestFunctionWithCustomData(Method method,
                                                                   Consumer<GameTestHelper> consumer,
                                                                   GameTestData gameTestData) {
        String declaredClassName = method.getDeclaringClass().getSimpleName().toLowerCase();
        String testId = declaredClassName + "." + method.getName().toLowerCase();
        return new TestFunction(
                gameTestData.batch,
                testId,
                gameTestData.template.isEmpty() ? testId : declaredClassName + "." + gameTestData.template,
                StructureUtils.getRotationForRotationSteps(gameTestData.rotationSteps),
                gameTestData.timeoutTicks,
                gameTestData.setupTicks,
                gameTestData.required,
                gameTestData.requiredSuccesses,
                gameTestData.attempts,
                consumer
        );
    }

    private static TestFunction generateTestFunctionWithCustomData(Method method, GameTestData gameTestData) {
        String string = method.getDeclaringClass().getSimpleName();
        String string2 = string.toLowerCase();
        String string3 = string2 + "." + method.getName().toLowerCase();
        String string4 = gameTestData.template.isEmpty() ? string3 : string2 + "." + gameTestData.template;
        String string5 = gameTestData.batch;
        Rotation rotation = StructureUtils.getRotationForRotationSteps(gameTestData.rotationSteps);
        return new TestFunction(string5, string3, string4, rotation, gameTestData.timeoutTicks, gameTestData.setupTicks, gameTestData.required, gameTestData.requiredSuccesses, gameTestData.attempts, turnMethodIntoConsumer(method));
    }

    private static Consumer<GameTestHelper> turnMethodIntoConsumer(Method method) {
        return (object) -> {
            try {
                Object object2 = method.getDeclaringClass().newInstance();
                method.invoke(object2, object);
            } catch (InvocationTargetException var3) {
                if (var3.getCause() instanceof RuntimeException) {
                    throw (RuntimeException)var3.getCause();
                } else {
                    throw new RuntimeException(var3.getCause());
                }
            } catch (ReflectiveOperationException var4) {
                throw new RuntimeException(var4);
            }
        };
    }

    private static Consumer<ServerLevel> turnBatchMethodIntoConsumer(Method method) {
        return (object) -> {
            try {
                Object object2 = method.getDeclaringClass().newInstance();
                method.invoke(object2, object);
            } catch (InvocationTargetException var3) {
                if (var3.getCause() instanceof RuntimeException) {
                    throw (RuntimeException)var3.getCause();
                } else {
                    throw new RuntimeException(var3.getCause());
                }
            } catch (ReflectiveOperationException var4) {
                throw new RuntimeException(var4);
            }
        };
    }

    private static <T extends Annotation> void registerBatchFunction(Method method, Class<T> clazz, Function<T, String> function, Map<String, Consumer<ServerLevel>> map) {
        T annotation = method.getAnnotation(clazz);
        if (annotation != null) {
            String string = function.apply(annotation);
            Consumer<ServerLevel> consumer = map.putIfAbsent(string, turnBatchMethodIntoConsumer(method));
            if (consumer != null) {
                throw new RuntimeException("Hey, there should only be one " + clazz + " method per batch. Batch '" + string + "' has more than one!");
            }
        }
    }

    private static Collection<TestFunction> useTestGeneratorMethod(Method method) {
        try {
            Object object = method.getDeclaringClass().newInstance();
            return (Collection)method.invoke(object);
        } catch (ReflectiveOperationException var2) {
            throw new RuntimeException(var2);
        }
    }

    @Getter
    @NoArgsConstructor
    public static class GameTestCalcBatch {

        private @Nullable String name = "";
        private final List<String> values = new ArrayList<>();
        private final List<TestFunctionGenerator> testFunctionGenerators = new ArrayList<>();

        public boolean hasName() {
            return name != null;
        }

        public void addGenerator(TestFunctionGenerator generator) {
            Set<String> difference = Sets.difference(Sets.newHashSet(generator.getValues()), Sets.newHashSet(this.getValues()));
            this.values.addAll(difference);
            this.testFunctionGenerators.add(generator);
            if (this.name != null) {
                if (this.name.isEmpty()) {
                    this.name = generator.getGameTestDataBuilder().batch$value;
                } else if (!this.name.equals(generator.getGameTestDataBuilder().batch$value)) {
                    this.name = null;
                }
            }
        }

        public boolean canAcceptGenerator(TestFunctionGenerator generator) {
            Set<String> difference = Sets.difference(Sets.newHashSet(generator.getValues()), Sets.newHashSet(this.getValues()));
            for (TestFunctionGenerator gen : testFunctionGenerators) {
                ParsedGameTestConfig gameTestConfig = gen.getGameTestConfig();
                if (gameTestConfig.ignored()) {
                    if (gameTestConfig.combined()) {
                        for (String val : gen.getValues()) {
                            if (difference.contains(val)) {
                                return false;
                            }
                        }
                    } else if (difference.containsAll(gen.getValues())) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    @Builder
    @AllArgsConstructor
    public static class GameTestData {
        @Builder.Default private int timeoutTicks = 100;
        @Builder.Default private String batch = "defaultBatch";
        @Builder.Default private int rotationSteps = 0;
        @Builder.Default private boolean required = true;
        @Builder.Default private String template = "";
        @Builder.Default private long setupTicks = 0L;
        @Builder.Default private int attempts = 1;
        @Builder.Default private int requiredSuccesses = 1;

        public static GameTestData from(GameTest gameTest) {
            return new GameTestData(gameTest.timeoutTicks(), gameTest.batch(), gameTest.rotationSteps(),
                    gameTest.required(), gameTest.template(), gameTest.setupTicks(),
                    gameTest.attempts(), gameTest.requiredSuccesses());
        }

        public static GameTestData.GameTestDataBuilder builderFrom(GameTest gameTest) {
            return GameTestData.builder()
                    .timeoutTicks(gameTest.timeoutTicks())
                    .batch(gameTest.batch())
                    .rotationSteps(gameTest.rotationSteps())
                    .required(gameTest.required())
                    .template(gameTest.template())
                    .setupTicks(gameTest.setupTicks())
                    .attempts(gameTest.attempts())
                    .requiredSuccesses(gameTest.requiredSuccesses());
        }
    }
}
