package dev.sunlit.bettersunlit.client;

import io.github.douira.glsl_transformer.ast.transform.ASTParser;

/**
 * Releases retained GLSL AST build/parse caches after Iris constructs a dimension shader pipeline.
 */
public final class IrisAstCachePerf {

    public static final int POST_LOAD_CACHE_CAPACITY = 16;
    private static volatile ASTParser transformer;

    private IrisAstCachePerf() {
    }

    public static void trimAstCaches() {
        // The convenience singleton is not the parser used by TransformPatcher.
        // Registration happens after that class has initialized, without forcing it to load.
        trimParser(transformer);
    }

    public static void registerTransformer(ASTParser parser) {
        transformer = parser;
    }

    public static void trimParser(ASTParser parser) {
        if (parser != null) {
            parser.setBuildCacheSizeAndClear(POST_LOAD_CACHE_CAPACITY);
            parser.setParseCacheSizeAndClear(POST_LOAD_CACHE_CAPACITY);
        }
    }
}
