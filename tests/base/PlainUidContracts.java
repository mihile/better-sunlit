import dev.sunlit.bettersunlit.client.JeiPlainUid;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.library.ingredients.subtypes.*;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Execute the real helper against real reviewed JEI classes with the actual accessor methods installed. */
public class PlainUidContracts {
    static int checks;
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    static class Plain implements ITypedIngredient<Object>,JeiPlainUid.PlainItem {
        final Object value;Plain(Object value){this.value=value;}
        @SuppressWarnings("unchecked") public IIngredientType<Object> getType(){return (IIngredientType<Object>)(Object)VanillaTypes.ITEM_STACK;}
        public Object getIngredient(){throw new AssertionError("plain UID constructed an ItemStack");}
        public Object sunlit$plainItem(){return value;}
    }
    static class UnknownHelper extends ItemStackHelper { UnknownHelper(SubtypeManager manager){super(manager,null,null);} }
    static class UnknownManager extends SubtypeManager {UnknownManager(SubtypeInterpreters interpreters){super(interpreters);}}
    static class UnknownInterpreters extends SubtypeInterpreters {}
    static IIngredientManager manager(Object helper){return (IIngredientManager)java.lang.reflect.Proxy.newProxyInstance(IIngredientManager.class.getClassLoader(),new Class<?>[]{IIngredientManager.class},(proxy,method,args)->{
        if(method.getName().equals("getIngredientHelper"))return helper;throw new AssertionError("unexpected manager call "+method);
    });}
    static <T extends ItemStackHelper> T helper(Class<T> type,mezz.jei.api.ingredients.subtypes.ISubtypeManager subtypes)throws Exception {
        Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        T helper=type.cast(((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(type));
        Field field=ItemStackHelper.class.getDeclaredField("subtypeManager");field.setAccessible(true);field.set(helper,subtypes);return helper;
    }
    public static int runFixture()throws Exception{
        SubtypeInterpreters registry=new SubtypeInterpreters();SubtypeManager subtypes=new SubtypeManager(registry);ItemStackHelper helper=helper(ItemStackHelper.class,subtypes);
        var manager=manager(helper);Object first=new Object(),second=new Object();var plain=new Plain(first);
        check(JeiPlainUid.tryUid(plain,manager)==first,"plain UID was not the raw base");
        Field map=SubtypeInterpreters.class.getDeclaredField("map");map.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Object,Object> interpreters=(Map<Object,Object>)map.get(registry);
        interpreters.put(first,new Object());
        check(JeiPlainUid.tryUid(plain,manager)==null,"runtime subtype registration ignored");
        check(JeiPlainUid.tryUid(new Plain(second),manager)==second,"another base inherited subtype status");
        interpreters.remove(first);check(JeiPlainUid.tryUid(plain,manager)==first,"subtype result leaked across lookups");
        check(JeiPlainUid.tryUid(plain,manager(helper(UnknownHelper.class,subtypes)))==null,"custom helper bypassed");
        check(JeiPlainUid.tryUid(plain,manager(helper(ItemStackHelper.class,new UnknownManager(registry))))==null,"custom subtype manager bypassed");
        check(JeiPlainUid.tryUid(plain,manager(helper(ItemStackHelper.class,new SubtypeManager(new UnknownInterpreters()))))==null,"custom interpreter registry bypassed");
        check(JeiPlainUid.tryUid(plain,manager(helper(ItemStackHelper.class,null)))==null,"missing subtype manager forced");
        ITypedIngredient<Object> opaque=new ITypedIngredient<>(){
            @SuppressWarnings("unchecked") public IIngredientType<Object> getType(){return (IIngredientType<Object>)(Object)VanillaTypes.ITEM_STACK;}
            public Object getIngredient(){throw new AssertionError("opaque ingredient inspected by fast path");}
        };
        check(JeiPlainUid.tryUid(opaque,manager)==null,"opaque/tagged ingredient forced");
        return checks;
    }
    static void dependencyGates()throws Exception {
        List<URL> urls=new ArrayList<>();
        for(String line:Files.readAllLines(Path.of(System.getProperty("sunlit.modern.classpath"))))urls.add(Path.of(line).toUri().toURL());
        String[] dependencies={"mezz/jei/library/ingredients/itemStacks/NormalizedTypedItem.class","mezz/jei/library/plugins/vanilla/ingredients/ItemStackHelper.class","mezz/jei/library/ingredients/subtypes/SubtypeManager.class","mezz/jei/library/ingredients/subtypes/SubtypeInterpreters.class","mezz/jei/library/recipes/collect/RecipeMap.class","mezz/jei/api/constants/VanillaTypes.class","mezz/jei/api/constants/VanillaTypes$1.class","mezz/jei/library/ingredients/itemStacks/TypedItemStack.class","net/minecraft/world/item/ItemStack.class"};
        for(String dependency:dependencies)for(boolean changed:new boolean[]{false,true})try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader()){
            @Override public java.io.InputStream getResourceAsStream(String name){
                var input=super.getResourceAsStream(name);if(!name.equals(dependency))return input;
                if(!changed){if(input!=null)try{input.close();}catch(Exception ignored){}return null;}
                if(input==null)throw new AssertionError("missing review dependency "+dependency);
                try(input){byte[] bytes=input.readAllBytes();return new java.io.ByteArrayInputStream(Arrays.copyOf(bytes,bytes.length+1));}catch(Exception error){throw new RuntimeException(error);}
            }
        }){
            Class<?> plugin=Class.forName("dev.sunlit.bettersunlit.inline.EntryMixinPlugin",true,loader);
            boolean allowed=(boolean)plugin.getMethod("canApplyGate",String.class,String.class).invoke(plugin.getConstructor().newInstance(),"mezz.jei.library.recipes.collect.RecipeMap","EntryJeiRecipeUidMixin");
            check(!allowed,"missing/changed UID dependency allowed: "+dependency);
        }
    }
    public static void main(String[] args)throws Exception{
        dependencyGates();
        Map<String,byte[]> patches=new HashMap<>();
        for(String[] pair:new String[][]{{"EntryJeiItemHelperMixin","mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper"},
                {"EntryJeiSubtypeManagerMixin","mezz.jei.library.ingredients.subtypes.SubtypeManager"},
                {"EntryJeiSubtypeInterpretersMixin","mezz.jei.library.ingredients.subtypes.SubtypeInterpreters"}}){
            ClassNode original=BaseOptimizationContracts.node(pair[1]),mix=BaseOptimizationContracts.node("dev.sunlit.fishvisuals.mixin."+pair[0]);
            original.interfaces.addAll(mix.interfaces);
            for(MethodNode method:mix.methods)if(method.name.startsWith("sunlit$")){
                for(var instruction:method.instructions)if(instruction instanceof FieldInsnNode field&&field.owner.equals(mix.name))field.owner=original.name;
                original.methods.add(method);
            }
            ClassWriter writer=new ClassWriter(0);original.accept(writer);patches.put(pair[1],writer.toByteArray());
        }
        // Only the ITEM_STACK constant is needed. Isolate unrelated fluid/platform initialization from this CPU fixture.
        var vanilla=BaseOptimizationContracts.node("mezz.jei.api.constants.VanillaTypes");
        var initializer=vanilla.methods.stream().filter(method->method.name.equals("<clinit>")).findFirst().orElseThrow();
        initializer.instructions.clear();initializer.tryCatchBlocks.clear();if(initializer.localVariables!=null)initializer.localVariables.clear();
        initializer.instructions.add(new TypeInsnNode(Opcodes.NEW,"mezz/jei/api/constants/VanillaTypes$1"));initializer.instructions.add(new InsnNode(Opcodes.DUP));
        initializer.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"mezz/jei/api/constants/VanillaTypes$1","<init>","()V",false));
        String descriptor=vanilla.fields.stream().filter(field->field.name.equals("ITEM_STACK")).findFirst().orElseThrow().desc;
        initializer.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC,vanilla.name,"ITEM_STACK",descriptor));initializer.instructions.add(new InsnNode(Opcodes.RETURN));initializer.maxStack=2;initializer.maxLocals=0;
        ClassWriter vanillaWriter=new ClassWriter(0);vanilla.accept(vanillaWriter);patches.put("mezz.jei.api.constants.VanillaTypes",vanillaWriter.toByteArray());
        List<URL> urls=new ArrayList<>();urls.add(PlainUidContracts.class.getProtectionDomain().getCodeSource().getLocation());
        for(String line:Files.readAllLines(Path.of(System.getProperty("sunlit.modern.classpath"))))urls.add(Path.of(line).toUri().toURL());
        try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader()){
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException{byte[] data=patches.get(name);return data==null?super.findClass(name):defineClass(name,data,0,data.length);}
        }){
            Object count=Class.forName("PlainUidContracts",true,loader).getMethod("runFixture").invoke(null);
            System.out.println("PASS "+((Integer)count+checks)+" actual JEI UID helper contracts; no ItemStack creation, live subtype changes and custom-helper fallbacks");
        }
    }
}
