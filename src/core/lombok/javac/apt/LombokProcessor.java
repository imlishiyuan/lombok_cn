/*
 * Copyright (C) 2009-2020 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.apt;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileManager;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.processing.JavacFiler;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;

import lombok.Lombok;
import lombok.core.CleanupRegistry;
import lombok.core.DiagnosticsReceiver;
import lombok.javac.JavacTransformer;
import lombok.permit.Permit;
import lombok.permit.dummy.Parent;
import sun.misc.Unsafe;

/**
 * This Annotation Processor is the standard injection mechanism for lombok-enabling the javac compiler.
 * 
 * To actually enable lombok in a javac compilation run, this class should be in the classpath when
 * running javac; that's the only requirement.
 */
@SupportedAnnotationTypes("*")
public class LombokProcessor extends AbstractProcessor {

	private ProcessingEnvironment processingEnv;
	private JavacProcessingEnvironment javacProcessingEnv;
	private JavacFiler javacFiler;
	private JavacTransformer transformer;
	private Trees trees;
	private boolean lombokDisabled = false;
	
	/** {@inheritDoc} */
	@Override public void init(ProcessingEnvironment procEnv) {
		super.init(procEnv);
		if (System.getProperty("lombok.disable") != null) {
			lombokDisabled = true;
			return;
		}

		this.processingEnv = procEnv;
		this.javacProcessingEnv = getJavacProcessingEnvironment(procEnv);
		this.javacFiler = getJavacFiler(procEnv.getFiler());

		placePostCompileAndDontMakeForceRoundDummiesHook();
		trees = Trees.instance(javacProcessingEnv);
		transformer = new JavacTransformer(procEnv.getMessager(), trees);
		SortedSet<Long> p = transformer.getPriorities();
		if (p.isEmpty()) {
			this.priorityLevels = new long[] {0L};
			this.priorityLevelsRequiringResolutionReset = new HashSet<Long>();
		} else {
			this.priorityLevels = new long[p.size()];
			int i = 0;
			for (Long prio : p) this.priorityLevels[i++] = prio;
			this.priorityLevelsRequiringResolutionReset = transformer.getPrioritiesRequiringResolutionReset();
		}
	}
	
	private static final String JPE = "com.sun.tools.javac.processing.JavacProcessingEnvironment";
	private static final Field javacProcessingEnvironment_discoveredProcs = getFieldAccessor(JPE, "discoveredProcs");
	private static final Field discoveredProcessors_procStateList = getFieldAccessor(JPE + "$DiscoveredProcessors", "procStateList");
	private static final Field processorState_processor = getFieldAccessor(JPE + "$processor", "processor");
	
	private static final Field getFieldAccessor(String typeName, String fieldName) {
		try {
			return Permit.getField(Class.forName(typeName), fieldName);
		} catch (ClassNotFoundException e) {
			return null;
		} catch (NoSuchFieldException e) {
			return null;
		}
	}
	
	// The intent of this method is to have lombok emit a warning if it's not 'first in line'. However, pragmatically speaking, you're always looking at one of two cases:
	// (A) The other processor(s) running before lombok require lombok to have run or they crash. So, they crash, and unfortunately we are never even init-ed; the warning is never emitted.
	// (B) The other processor(s) don't care about it at all. So, it doesn't actually matter that lombok isn't first.
	// Hence, for now, no warnings.
	@SuppressWarnings("unused")
	private String listAnnotationProcessorsBeforeOurs() {
		try {
			Object discoveredProcessors = javacProcessingEnvironment_discoveredProcs.get(this.javacProcessingEnv);
			ArrayList<?> states = (ArrayList<?>) discoveredProcessors_procStateList.get(discoveredProcessors);
			if (states == null || states.isEmpty()) return null;
			if (states.size() == 1) return processorState_processor.get(states.get(0)).getClass().getName();
			
			int idx = 0;
			StringBuilder out = new StringBuilder();
			for (Object processState : states) {
				idx++;
				String name = processorState_processor.get(processState).getClass().getName();
				if (out.length() > 0) out.append(", ");
				out.append("[").append(idx).append("] ").append(name);
			}
			return out.toString();
		} catch (Exception e) {
			return null;
		}
	}
	
	private void placePostCompileAndDontMakeForceRoundDummiesHook() {
		stopJavacProcessingEnvironmentFromClosingOurClassloader();
		
		forceMultipleRoundsInNetBeansEditor();
		Context context = javacProcessingEnv.getContext();
		disablePartialReparseInNetBeansEditor(context);
		try {
			Method keyMethod = Permit.getMethod(Context.class, "key", Class.class);
			Object key = Permit.invoke(keyMethod, context, JavaFileManager.class);
			Field htField = Permit.getField(Context.class, "ht");
			@SuppressWarnings("unchecked")
			Map<Object,Object> ht = (Map<Object,Object>) Permit.get(htField, context);
			final JavaFileManager originalFiler = (JavaFileManager) ht.get(key);
			if (!(originalFiler instanceof InterceptingJavaFileManager)) {
				final Messager messager = processingEnv.getMessager();
				DiagnosticsReceiver receiver = new MessagerDiagnosticsReceiver(messager);
				
				JavaFileManager newFilerManager = new InterceptingJavaFileManager(originalFiler, receiver);
				ht.put(key, newFilerManager);
				Field filerFileManagerField = Permit.getField(JavacFiler.class, "fileManager");
				filerFileManagerField.set(javacFiler, newFilerManager);
				
				if (lombok.javac.Javac.getJavaCompilerVersion() > 8
						&& !lombok.javac.handlers.JavacHandlerUtil.inNetbeansCompileOnSave(context)) {
					replaceFileManagerJdk9(context, newFilerManager);
				}
			}
		} catch (Exception e) {
			throw Lombok.sneakyThrow(e);
		}
	}

	private void replaceFileManagerJdk9(Context context, JavaFileManager newFiler) {
		try {
			JavaCompiler compiler = (JavaCompiler) Permit.invoke(Permit.getMethod(JavaCompiler.class, "instance", Context.class), null, context);
			try {
				Field fileManagerField = Permit.getField(JavaCompiler.class, "fileManager");
				Permit.set(fileManagerField, compiler, newFiler);
			}
			catch (Exception e) {}
			
			try {
				Field writerField = Permit.getField(JavaCompiler.class, "writer");
				ClassWriter writer = (ClassWriter) writerField.get(compiler);
				Field fileManagerField = Permit.getField(ClassWriter.class, "fileManager");
				Permit.set(fileManagerField, writer, newFiler);
			}
			catch (Exception e) {}
		}
		catch (Exception e) {
		}
	}
	
	private void forceMultipleRoundsInNetBeansEditor() {
		try {
			Field f = Permit.getField(JavacProcessingEnvironment.class, "isBackgroundCompilation");
			f.set(javacProcessingEnv, true);
		} catch (NoSuchFieldException e) {
			// only NetBeans has it
		} catch (Throwable t) {
			throw Lombok.sneakyThrow(t);
		}
	}
	
	private void disablePartialReparseInNetBeansEditor(Context context) {
		try {
			Class<?> cancelServiceClass = Class.forName("com.sun.tools.javac.util.CancelService");
			Method cancelServiceInstance = Permit.getMethod(cancelServiceClass, "instance", Context.class);
			Object cancelService = Permit.invoke(cancelServiceInstance, null, context);
			if (cancelService == null) return;
			Field parserField = Permit.getField(cancelService.getClass(), "parser");
			Object parser = parserField.get(cancelService);
			Field supportsReparseField = Permit.getField(parser.getClass(), "supportsReparse");
			supportsReparseField.set(parser, false);
		} catch (ClassNotFoundException e) {
			// only NetBeans has it
		} catch (NoSuchFieldException e) {
			// only NetBeans has it
		} catch (Throwable t) {
			throw Lombok.sneakyThrow(t);
		}
	}
	
	private static ClassLoader wrapClassLoader(final ClassLoader parent) {
		return new ClassLoader() {
			
			public Class<?> loadClass(String name) throws ClassNotFoundException {
				return parent.loadClass(name);
			}
			
			public String toString() {
				return parent.toString();
			}
			
			public URL getResource(String name) {
				return parent.getResource(name);
			}
			
			public Enumeration<URL> getResources(String name) throws IOException {
				return parent.getResources(name);
			}
			
			public InputStream getResourceAsStream(String name) {
				return parent.getResourceAsStream(name);
			}
			
			public void setDefaultAssertionStatus(boolean enabled) {
				parent.setDefaultAssertionStatus(enabled);
			}
			
			public void setPackageAssertionStatus(String packageName, boolean enabled) {
				parent.setPackageAssertionStatus(packageName, enabled);
			}
			
			public void setClassAssertionStatus(String className, boolean enabled) {
				parent.setClassAssertionStatus(className, enabled);
			}
			
			public void clearAssertionStatus() {
				parent.clearAssertionStatus();
			}
		};
	}
	
	private void stopJavacProcessingEnvironmentFromClosingOurClassloader() {
		try {
			Field f = Permit.getField(JavacProcessingEnvironment.class, "processorClassLoader");
			ClassLoader unwrapped = (ClassLoader) f.get(javacProcessingEnv);
			if (unwrapped == null) return;
			ClassLoader wrapped = wrapClassLoader(unwrapped);
			f.set(javacProcessingEnv, wrapped);
		} catch (NoSuchFieldException e) {
			// Some versions of javac have this (and call close on it), some don't. I guess this one doesn't have it.
		} catch (Throwable t) {
			throw Lombok.sneakyThrow(t);
		}
	}
	
	private final IdentityHashMap<JCCompilationUnit, Long> roots = new IdentityHashMap<JCCompilationUnit, Long>();
	private long[] priorityLevels;
	private Set<Long> priorityLevelsRequiringResolutionReset;
	private CleanupRegistry cleanup = new CleanupRegistry();
	
	/** {@inheritDoc} */
	@Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (lombokDisabled) return false;
		if (roundEnv.processingOver()) {
			cleanup.run();
			return false;
		}
		
		// We have: A sorted set of all priority levels: 'priorityLevels'
		
		// Step 1: Take all CUs which aren't already in the map. Give them the first priority level.
		
		for (Element element : roundEnv.getRootElements()) {
			JCCompilationUnit unit = toUnit(element);
			if (unit == null) continue;
			if (roots.containsKey(unit)) continue;
			roots.put(unit, priorityLevels[0]);
		}
		
		while (true) {
			// Step 2: For all CUs (in the map, not the roundEnv!), run them across all handlers at their current prio level.
			
			for (long prio : priorityLevels) {
				List<JCCompilationUnit> cusForThisRound = new ArrayList<JCCompilationUnit>();
				for (Map.Entry<JCCompilationUnit, Long> entry : roots.entrySet()) {
					Long prioOfCu = entry.getValue();
					if (prioOfCu == null || prioOfCu != prio) continue;
					cusForThisRound.add(entry.getKey());
				}
				transformer.transform(prio, javacProcessingEnv.getContext(), cusForThisRound, cleanup);
			}
			
			// Step 3: Push up all CUs to the next level. Set level to null if there is no next level.
			
			Set<Long> newLevels = new HashSet<Long>();
			for (int i = priorityLevels.length - 1; i >= 0; i--) {
				Long curLevel = priorityLevels[i];
				Long nextLevel = (i == priorityLevels.length - 1) ? null : priorityLevels[i + 1];
				List<JCCompilationUnit> cusToAdvance = new ArrayList<JCCompilationUnit>();
				for (Map.Entry<JCCompilationUnit, Long> entry : roots.entrySet()) {
					if (curLevel.equals(entry.getValue())) {
						cusToAdvance.add(entry.getKey());
						newLevels.add(nextLevel);
					}
				}
				for (JCCompilationUnit unit : cusToAdvance) {
					roots.put(unit, nextLevel);
				}
			}
			newLevels.remove(null);
			
			// Step 4: If ALL values are null, quit. Else, either do another loop right now or force a resolution reset by forcing a new round in the annotation processor.
			
			if (newLevels.isEmpty()) return false;
			newLevels.retainAll(priorityLevelsRequiringResolutionReset);
			if (!newLevels.isEmpty()) {
				// Force a new round to reset resolution. The next round will cause this method (process) to be called again.
				forceNewRound(javacFiler);
				return false;
			}
			// None of the new levels need resolution, so just keep going.
		}
	}
	
	private int dummyCount = 0;
	private void forceNewRound(JavacFiler filer) {
		if (!filer.newFiles()) {
			try {
				filer.getGeneratedSourceNames().add("lombok.dummy.ForceNewRound" + (dummyCount++));
			} catch (Exception e) {
				e.printStackTrace();
				processingEnv.getMessager().printMessage(Kind.WARNING,
					"Can't force a new processing round. Lombok won't work.");
			}
		}
	}

	private JCCompilationUnit toUnit(Element element) {
		TreePath path = null;
		if (trees != null) {
			try {
				path = trees.getPath(element);
			} catch (NullPointerException ignore) {
				// Happens if a package-info.java doesn't contain a package declaration.
				// https://github.com/projectlombok/lombok/issues/2184
				// We can safely ignore those, since they do not need any processing
			}
		}
		if (path == null) return null;
		
		return (JCCompilationUnit) path.getCompilationUnit();
	}
	
	/**
	 * We just return the latest version of whatever JDK we run on. Stupid? Yeah, but it's either that or warnings on all versions but 1.
	 */
	@Override public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latest();
	}
	
	/**
	 * This class casts the given processing environment to a JavacProcessingEnvironment. In case of
	 * gradle incremental compilation, the delegate ProcessingEnvironment of the gradle wrapper is returned.
	 */
	public JavacProcessingEnvironment getJavacProcessingEnvironment(Object procEnv) {
		addOpensForLombok();
		if (procEnv instanceof JavacProcessingEnvironment) return (JavacProcessingEnvironment) procEnv;
		
		// try to find a "delegate" field in the object, and use this to try to obtain a JavacProcessingEnvironment
		for (Class<?> procEnvClass = procEnv.getClass(); procEnvClass != null; procEnvClass = procEnvClass.getSuperclass()) {
			Object delegate = tryGetDelegateField(procEnvClass, procEnv);
			if (delegate == null) delegate = tryGetProxyDelegateToField(procEnvClass, procEnv);
			if (delegate == null) delegate = tryGetProcessingEnvField(procEnvClass, procEnv);
			
			if (delegate != null) return getJavacProcessingEnvironment(delegate);
			// delegate field was not found, try on superclass
		}
		
		processingEnv.getMessager().printMessage(Kind.WARNING,
			"Can't get the delegate of the gradle IncrementalProcessingEnvironment. Lombok won't work.");
		return null;
	}
	
	private static Object getOwnModule() {
		try {
			Method m = Permit.getMethod(Class.class, "getModule");
			return m.invoke(LombokProcessor.class);
		} catch (Exception e) {
			return null;
		}
	}
	
	private static Object getJdkCompilerModule() {
		/* call public api: ModuleLayer.boot().findModule("jdk.compiler").get();
		   but use reflection because we don't want this code to crash on jdk1.7 and below.
		   In that case, none of this stuff was needed in the first place, so we just exit via
		   the catch block and do nothing.
		 */
		
		try {
			Class<?> cModuleLayer = Class.forName("java.lang.ModuleLayer");
			Method mBoot = cModuleLayer.getDeclaredMethod("boot");
			Object bootLayer = mBoot.invoke(null);
			Class<?> cOptional = Class.forName("java.util.Optional");
			Method mFindModule = cModuleLayer.getDeclaredMethod("findModule", String.class);
			Object oCompilerO = mFindModule.invoke(bootLayer, "jdk.compiler");
			return cOptional.getDeclaredMethod("get").invoke(oCompilerO);
		} catch (Exception e) {
			return null;
		}
	}
	
	/** Useful from jdk9 and up; required from jdk16 and up. This code is supposed to gracefully do nothing on jdk8 and below, as this operation isn't needed there. */
	public static void addOpensForLombok() {
		Class<?> cModule;
		try {
			cModule = Class.forName("java.lang.Module");
		} catch (ClassNotFoundException e) {
			return; //jdk8-; this is not needed.
		}
		
		Unsafe unsafe = getUnsafe();
		Object jdkCompilerModule = getJdkCompilerModule();
		Object ownModule = getOwnModule();
		String[] allPkgs = {
			"com.sun.tools.javac.code",
			"com.sun.tools.javac.comp",
			"com.sun.tools.javac.file",
			"com.sun.tools.javac.main",
			"com.sun.tools.javac.model",
			"com.sun.tools.javac.parser",
			"com.sun.tools.javac.processing",
			"com.sun.tools.javac.tree",
			"com.sun.tools.javac.util",
			"com.sun.tools.javac.jvm",
		};
		
		try {
			Method m = cModule.getDeclaredMethod("implAddOpens", String.class, cModule);
			long firstFieldOffset = getFirstFieldOffset(unsafe);
			unsafe.putBooleanVolatile(m, firstFieldOffset, true);
			for (String p : allPkgs) m.invoke(jdkCompilerModule, p, ownModule);
		} catch (Exception ignore) {}
	}
	
	private static long getFirstFieldOffset(Unsafe unsafe) {
		try {
			return unsafe.objectFieldOffset(Parent.class.getDeclaredField("first"));
		} catch (NoSuchFieldException e) {
			// can't happen.
			throw new RuntimeException(e);
		} catch (SecurityException e) {
			// can't happen
			throw new RuntimeException(e);
		}
	}
	
	private static Unsafe getUnsafe() {
		try {
			Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			return (Unsafe) theUnsafe.get(null);
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * This class returns the given filer as a JavacFiler. In case the filer is no
	 * JavacFiler (e.g. the Gradle IncrementalFiler), its "delegate" field is used to get the JavacFiler
	 * (directly or through a delegate field again)
	 */
	public JavacFiler getJavacFiler(Object filer) {
		if (filer instanceof JavacFiler) return (JavacFiler) filer;
		
		// try to find a "delegate" field in the object, and use this to check for a JavacFiler
		for (Class<?> filerClass = filer.getClass(); filerClass != null; filerClass = filerClass.getSuperclass()) {
			Object delegate = tryGetDelegateField(filerClass, filer);
			if (delegate == null) delegate = tryGetProxyDelegateToField(filerClass, filer);
			if (delegate == null) delegate = tryGetFilerField(filerClass, filer);
			
			if (delegate != null) return getJavacFiler(delegate);
			// delegate field was not found, try on superclass
		}
		
		processingEnv.getMessager().printMessage(Kind.WARNING,
			"Can't get a JavacFiler from " + filer.getClass().getName() + ". Lombok won't work.");
		return null;
	}

	/**
	 * Gradle incremental processing
	 */
	private Object tryGetDelegateField(Class<?> delegateClass, Object instance) {
		try {
			return Permit.getField(delegateClass, "delegate").get(instance);
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * Kotlin incremental processing
	 */
	private Object tryGetProcessingEnvField(Class<?> delegateClass, Object instance) {
		try {
			return Permit.getField(delegateClass, "processingEnv").get(instance);
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * Kotlin incremental processing
	 */
	private Object tryGetFilerField(Class<?> delegateClass, Object instance) {
		try {
			return Permit.getField(delegateClass, "filer").get(instance);
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * IntelliJ IDEA >= 2020.3
	 */
	private Object tryGetProxyDelegateToField(Class<?> delegateClass, Object instance) {
		try {
			InvocationHandler handler = Proxy.getInvocationHandler(instance);
			return Permit.getField(handler.getClass(), "val$delegateTo").get(handler);
		} catch (Exception e) {
			return null;
		}
	}
}
