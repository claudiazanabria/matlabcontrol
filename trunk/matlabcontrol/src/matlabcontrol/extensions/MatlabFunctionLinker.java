package matlabcontrol.extensions;

/*
 * Copyright (c) 2011, Joshua Kaplan
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *  - Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 *  - Neither the name of matlabcontrol nor the names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import matlabcontrol.MatlabInvocationException;
import matlabcontrol.MatlabProxy;
import matlabcontrol.MatlabProxy.MatlabThreadProxy;
import matlabcontrol.extensions.MatlabReturns.MatlabReturnN;
import matlabcontrol.extensions.MatlabType.MatlabTypeSerializedGetter;

/**
 *
 * @since 4.1.0
 * 
 * @author <a href="mailto:nonother@gmail.com">Joshua Kaplan</a>
 */
public class MatlabFunctionLinker
{    
    private MatlabFunctionLinker() { }
    
    
    /**************************************************************************************************************\
    |*                                        Linking & Validation                                                *|
    \**************************************************************************************************************/
    
    
    public static <T> T link(Class<T> functionInterface, MatlabProxy matlabProxy)
    {
        if(!functionInterface.isInterface())
        {
            throw new LinkingException(functionInterface.getCanonicalName() + " is not an interface");
        }
        
        //Maps each method to information about the function and method
        Map<Method, InvocationInfo> resolvedInfo = new ConcurrentHashMap<Method, InvocationInfo>();
        
        //Validate and retrieve information about all of the methods in the interface
        for(Method method : functionInterface.getMethods())
        {
            MatlabFunctionInfo annotation = method.getAnnotation(MatlabFunctionInfo.class);
            
            if(annotation == null)
            {
                throw new LinkingException(method + " is not annotated with " + 
                        MatlabFunctionInfo.class.getCanonicalName());
            }
            
            if(!Arrays.asList(method.getExceptionTypes()).contains(MatlabInvocationException.class))
            {
                throw new LinkingException(method + " does not throw " +
                        MatlabInvocationException.class.getCanonicalName());
            }
            
            //Build information about how to invoke the method, performing validation in the process
            FunctionInfo functionInfo = getFunctionInfo(method, annotation);
            Class<?>[] returnTypes = getReturnTypes(method, annotation);
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean usesMatlabTypes = usesMatlabTypes(returnTypes, parameterTypes);
            InvocationInfo invocationInfo = new InvocationInfo(functionInfo.name, functionInfo.containingDirectory,
                    returnTypes, parameterTypes, usesMatlabTypes);
            resolvedInfo.put(method, invocationInfo);
        }
        
        T functionProxy = (T) Proxy.newProxyInstance(functionInterface.getClassLoader(),
                new Class<?>[] { functionInterface }, new MatlabFunctionInvocationHandler(matlabProxy, resolvedInfo));
        
        return functionProxy;
    }
    
    private static class FunctionInfo
    {
        final String name;
        final String containingDirectory;
        
        public FunctionInfo(String name, String containingDirectory)
        {
            this.name = name;
            this.containingDirectory = containingDirectory;
        }
    }
    
    private static FunctionInfo getFunctionInfo(Method method, MatlabFunctionInfo annotation)
    {           
        //Verify exactly one of name, absolutePath, and relativePath has been specified
        boolean hasName = !annotation.name().isEmpty();
        boolean hasAbsolutePath = !annotation.absolutePath().isEmpty();
        boolean hasRelativePath = !annotation.relativePath().isEmpty();
        if( (hasName && (hasAbsolutePath || hasRelativePath)) ||
            (hasAbsolutePath && (hasName || hasRelativePath)) ||
            (hasRelativePath && (hasAbsolutePath || hasName)) ||
            (!hasName && !hasAbsolutePath && !hasRelativePath) )
        {
            throw new LinkingException(method + "'s annotation must specify either a function name, an absolute " +
                    "path, or a relative path. It must specify exactly one.");
        }
        
        //Determine the function's name and if applicable, the directory the function is located in
        String functionName;
        String containingDirectory;

        //If the name was specified, meaning the function is expected to be on MATLAB's path
        if(!annotation.name().isEmpty())
        {
            functionName = annotation.name();
            containingDirectory = null;
        }
        else
        {
            String path; //Only need for exception messages
            File mFile;
            
            if(!annotation.absolutePath().isEmpty())
            {
                path = annotation.absolutePath();
                
                mFile = new File(annotation.absolutePath());
            }
            else
            {
                path = annotation.relativePath();
                
                File interfaceLocation = getClassLocation(method.getDeclaringClass());
                try
                {   
                    //If this line succeeds then, then the interface is inside of a jar, so the m-file is as well
                    JarFile jar = new JarFile(interfaceLocation);

                    JarEntry entry = jar.getJarEntry(annotation.relativePath());

                    if(entry == null)
                    {
                         throw new LinkingException("Unable to find m-file inside of jar\n" +
                            "method: " + method.getName() + "\n" +
                            "path: " + annotation.relativePath() + "\n" +
                            "jar location: " + interfaceLocation.getAbsolutePath());
                    }

                    String entryName = entry.getName();
                    if(!entryName.endsWith(".m"))
                    {
                        throw new LinkingException("Specified m-file does not end in .m\n" +
                                "method: " + method.getName() + "\n" +
                                "path: " + annotation.relativePath() + "\n" +
                                "jar location: " + interfaceLocation.getAbsolutePath());
                    }

                    functionName = entryName.substring(entryName.lastIndexOf("/") + 1, entryName.length() - 2);
                    mFile = extractFromJar(jar, entry, functionName, method, interfaceLocation, annotation);
                    jar.close();
                }
                //Interface is not located inside a jar, so neither is the m-file
                catch(IOException e)
                {
                    mFile = new File(interfaceLocation, annotation.relativePath());
                }
            }
            
            //Resolve canonical path
            try
            {
                mFile = mFile.getCanonicalFile();
            }
            catch(IOException ex)
            {
                throw new LinkingException("Unable to resolve canonical path of specified function\n" +
                        "method: " + method.getName() + "\n" +
                        "path:" + path + "\n" +
                        "non-canonical path: " + mFile.getAbsolutePath(), ex);
            }
            
            //Validate file location
            if(!mFile.exists())
            {
                throw new LinkingException("Specified m-file does not exist\n" + 
                        "method: " + method.getName() + "\n" +
                        "path: " + path + "\n" +
                        "resolved as: " + mFile.getAbsolutePath());
            }
            if(!mFile.isFile())
            {
                throw new LinkingException("Specified m-file is not a file\n" + 
                        "method: " + method.getName() + "\n" +
                        "path: " + path + "\n" +
                        "resolved as: " + mFile.getAbsolutePath());
            }
            if(!mFile.getName().endsWith(".m"))
            {
                throw new LinkingException("Specified m-file does not end in .m\n" + 
                        "method: " + method.getName() + "\n" +
                        "path: " + path + "\n" +
                        "resolved as: " + mFile.getAbsolutePath());
            }
            
            //Parse out the name of the function and the directory containing it
            containingDirectory = mFile.getParent();
            functionName = mFile.getName().substring(0, mFile.getName().length() - 2); 
        }
        
        return new FunctionInfo(functionName, containingDirectory);
    }
    
    /**
     * Extracts the {@code entry} belonging to {@code jar} to a file named {@code functionName}.m that is placed in
     * the directory specified by the property {@code java.io.tmpdir}. It is not placed directly in that directory, but
     * instead inside a directory with a randomly generated name. The file is deleted upon JVM termination.
     * 
     * @param jar
     * @param entry
     * @param functionName
     * @param method  used only for exception message
     * @param interfaceLocation  used only for exception message
     * @param annotation   used only for exception message
     * @return
     * @throws LinkingException 
     */
    private static File extractFromJar(JarFile jar, JarEntry entry, String functionName,
            Method method, File interfaceLocation, MatlabFunctionInfo annotation)
    {
        try
        {
            //Source
            InputStream entryStream = jar.getInputStream(entry);

            //Destination
            File tempDir = new File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString());
            File destFile = new File(tempDir, functionName + ".m");
            if(destFile.exists())
            {
                throw new LinkingException("Unable to extract m-file, randomly generated path already defined\n" +
                        "method: " + method + "\n" +
                        "function: " + functionName + "\n" +
                        "generated path: " + destFile.getAbsolutePath());
            }
            destFile.getParentFile().mkdirs();
            destFile.deleteOnExit();

            //Copy source to destination
            final int BUFFER_SIZE = 2048;
            byte[] buffer = new byte[BUFFER_SIZE];
            BufferedOutputStream dest = new BufferedOutputStream(new FileOutputStream(destFile), BUFFER_SIZE);
            int count;
            while((count = entryStream.read(buffer, 0, BUFFER_SIZE)) != -1)
            {
               dest.write(buffer, 0, count);
            }
            dest.flush();
            dest.close();

            return destFile;
        }
        catch(IOException e)
        {
            throw new LinkingException("Unable to extract m-file from jar\n" +
                "method: " + method.getName() + "\n" +
                "path: " + annotation.relativePath() + "\n" +
                "jar location: " + interfaceLocation.getAbsolutePath(), e);
        }
    }
    
    private static File getClassLocation(Class<?> clazz)
    {
        try
        {
            URL url = clazz.getProtectionDomain().getCodeSource().getLocation();
            File file = new File(url.toURI().getPath()).getCanonicalFile();
            
            return file;
        }
        catch(IOException e)
        {
            throw new LinkingException("Unable to determine location of " + clazz.getName(), e);
        }
        catch(URISyntaxException e)
        {
            throw new LinkingException("Unable to determine location of " + clazz.getName(), e);
        }
    }
    
    private static Class<?>[] getReturnTypes(Method method, MatlabFunctionInfo annotation)
    {
        //The return type of the method
        Class<?> methodReturn = method.getReturnType();
        
        //The types to be returned from the MATLAB function
        Class<?>[] annotatedReturns = annotation.returnTypes();
        
        //The return types to be determined
        Class<?>[] returnTypes;
        
        //Use method's return type
        if(annotatedReturns.length == 0)
        {
            if(method.getReturnType().equals(Void.TYPE))
            {
                returnTypes = new Class<?>[0];
            }
            else
            {
                returnTypes = new Class<?>[] { method.getReturnType() };
            }
        }
        //Use annotated returns
        else
        {
            //Validate the data
            if(methodReturn.isArray())
            {
                //Check that the array return type can hold all of the annotated return types
                Class<?> arrayType = methodReturn.getComponentType();
                for(Class<?> annotatedReturn : annotatedReturns)
                {
                    if(!arrayType.isAssignableFrom(annotatedReturn))
                    {
                        throw new LinkingException(method + "'s return type of " + methodReturn.getCanonicalName() +
                                " cannot hold the annotated return type " + annotatedReturn.getCanonicalName());
                    }
                }
            }
            else if(MatlabReturnN.class.isAssignableFrom(methodReturn))
            {
                int returnAmount = MatlabReturns.getNumberOfReturns((Class<? extends MatlabReturnN>) methodReturn);
                if(returnAmount != annotatedReturns.length)
                {
                    throw new LinkingException(method + " has a differing amount of return arguments specified by " +
                            "the return type and the number of annotated types\n" +
                            "Return Type: " + methodReturn.getCanonicalName() + "\n" +
                            "Return Type Count: " + returnAmount + "\n" +
                            "Annotated Return Types: " + Arrays.toString(annotatedReturns) + "\n" +
                            "Annotated Return Types Count: " + annotatedReturns.length);
                }
            }
            else
            {
                throw new LinkingException(method + " has annotated return types but provides an incompatible method " +
                        "return type. The method's return type must either be an array or a MatlabReturN class where " +
                        "N is an integer");
            }
            
            //Return types are the annotated types
            returnTypes = annotation.returnTypes();
        }
        
        return returnTypes;
    }
    
    private static boolean usesMatlabTypes(Class<?>[] returnTypes, Class<?>[] parameterTypes)
    {
        boolean usesMatlabTypes = false;

        List<Class<?>> types = new ArrayList<Class<?>>();
        types.addAll(Arrays.asList(returnTypes));
        types.addAll(Arrays.asList(parameterTypes));

        for(Class<?> type : types)
        {
            if(MatlabType.class.isAssignableFrom(type))
            {
                usesMatlabTypes = true;
                break;
            }
        }

        return usesMatlabTypes;
    }
    
    /**
     * A holder of information about the Java method and the associated MATLAB function.
     */
    private static class InvocationInfo implements Serializable
    {
        /**
         * The name of the function.
         */
        final String name;
        
        /**
         * The directory containing the function. This is an absolute path to an m-file on the system, never inside of
         * a compressed file such as a jar. {@code null} if the function is (supposed to be) on MATLAB's path.
         */
        final String containingDirectory;
        
        /**
         * The types of each returned argument. The length of this array is the nargout to call the function with.
         */
        final Class<?>[] returnTypes;
        
        /**
         * The declared types of the arguments of the method associated with the function.
         */
        final Class<?>[] parameterTypes;
        
        /**
         * If any of {@link #returnTypes} or {@link #parameterTypes} is a subclass of {@link MatlabType}s.
         */
        final boolean usesMatlabTypes;
        
        private InvocationInfo(String name, String containingDirectory,
                Class<?>[] returnTypes, Class<?>[] parameterTypes, boolean usesMatlabTypes)
        {
            this.name = name;
            this.containingDirectory = containingDirectory;
            
            this.returnTypes = returnTypes;
            this.parameterTypes = parameterTypes;
            this.usesMatlabTypes = usesMatlabTypes;
        }
    }
    
    /**
     * Represents an issue linking a Java method to a MATLAB function.
     * 
     * @since 4.1.0
     * @author <a href="mailto:nonother@gmail.com">Joshua Kaplan</a>
     */
    public static class LinkingException extends RuntimeException
    {
        private LinkingException(String msg)
        {
            super(msg);
        }
        
        private LinkingException(String msg, Throwable cause)
        {
            super(msg, cause);
        }
    }
    
    
    /**************************************************************************************************************\
    |*                                        Function Invocation                                                 *|
    \**************************************************************************************************************/
    

    private static class MatlabFunctionInvocationHandler implements InvocationHandler
    {
        private final MatlabProxy _proxy;
        private final Map<Method, InvocationInfo> _functionsInfo;
        
        private MatlabFunctionInvocationHandler(MatlabProxy proxy, Map<Method, InvocationInfo> functionsInfo)
        {
            _proxy = proxy;
            _functionsInfo = functionsInfo;
        }

        @Override
        public Object invoke(Object o, Method method, Object[] args) throws MatlabInvocationException
        {   
            InvocationInfo functionInfo = _functionsInfo.get(method);
            
            //Invoke the function
            Object[] returnValues;
            if(functionInfo.usesMatlabTypes)
            {
                //Replace all arguments with parameters of a MatlabType subclass with their serialized setters
                //(This intentionally means that if the declared type is not a MatlabType but the actual argument is
                //that it will not be transformed. This makes the behavior consistent with explication declaration for
                //return types.)
                for(int i = 0; i < args.length; i++)
                {
                    if(args[i] != null && MatlabType.class.isAssignableFrom(functionInfo.parameterTypes[i]))
                    {
                        args[i] = ((MatlabType) args[i]).getSerializedSetter();
                    }
                }
                
                returnValues = _proxy.invokeAndWait(new CustomFunctionInvocation(functionInfo, args));
                
                //For each returned value that is a serialized getter, deserialize it
                for(int i = 0; i < returnValues.length; i++)
                {
                    if(returnValues[i] instanceof MatlabTypeSerializedGetter)
                    {
                        returnValues[i] = ((MatlabType.MatlabTypeSerializedGetter) returnValues[i]).deserialize();
                    }
                }
            }
            else
            {
                returnValues = _proxy.invokeAndWait(new StandardFunctionInvocation(functionInfo, args));
            }
            
            //Process the returned values
            return processReturnValues(returnValues, functionInfo.returnTypes, method.getReturnType()); 
        }
        
        private Object processReturnValues(Object[] result, Class<?>[] returnTypes, Class<?> methodReturn)
        {
            Object toReturn;
            
            if(result.length == 0)
            {
                toReturn = result;
            }
            else if(result.length == 1)
            {
                toReturn = convertToType(result[0], returnTypes[0]);
            }
            else
            {
                Object newValuesArray;
                if(methodReturn.isArray())
                {
                    newValuesArray = Array.newInstance(methodReturn.getComponentType(), result.length);
                }
                else
                {
                    newValuesArray = new Object[result.length];
                }
                
                for(int i = 0; i < result.length; i++)
                {
                    if(result[i] != null)
                    {
                        Array.set(newValuesArray, i, convertToType(result[i], returnTypes[i]));
                    }
                }
                
                if(MatlabReturnN.class.isAssignableFrom(methodReturn))
                {
                    toReturn = MatlabReturns.createMatlabReturn((Object[]) newValuesArray);
                }
                else
                {
                    toReturn = newValuesArray;
                }
            }
            
            return toReturn;
        }
        
        private Object convertToType(Object value, Class<?> returnType)
        {
            Object toReturn;
            if(value == null)
            {
                toReturn = null;
            }
            else if(returnType.isPrimitive())
            {
                toReturn = convertPrimitiveReturnType(value, returnType);
            }
            else
            {
                if(!returnType.isAssignableFrom(value.getClass()))
                {
                    throw new IncompatibleReturnException("Required return type is incompatible with the type " +
                            "actually returned\n" +
                            "Required type: " + returnType.getCanonicalName() + "\n" +
                            "Returned type: " + value.getClass().getCanonicalName());
                }
                
                toReturn = value;
            }
            
            return toReturn;
        }

        private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_AUTOBOXED = new ConcurrentHashMap<Class<?>, Class<?>>();
        static
        {
            PRIMITIVE_TO_AUTOBOXED.put(byte.class, Byte.class);
            PRIMITIVE_TO_AUTOBOXED.put(short.class, Short.class);
            PRIMITIVE_TO_AUTOBOXED.put(int.class, Integer.class);
            PRIMITIVE_TO_AUTOBOXED.put(long.class, Long.class);
            PRIMITIVE_TO_AUTOBOXED.put(double.class, Double.class);
            PRIMITIVE_TO_AUTOBOXED.put(float.class, Float.class);
            PRIMITIVE_TO_AUTOBOXED.put(boolean.class, Boolean.class);
            PRIMITIVE_TO_AUTOBOXED.put(char.class, Character.class);
        }
        
        private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_ARRAY_OF = new ConcurrentHashMap<Class<?>, Class<?>>();
        static
        {
            PRIMITIVE_TO_ARRAY_OF.put(byte.class, byte[].class);
            PRIMITIVE_TO_ARRAY_OF.put(short.class, short[].class);
            PRIMITIVE_TO_ARRAY_OF.put(int.class, int[].class);
            PRIMITIVE_TO_ARRAY_OF.put(long.class, long[].class);
            PRIMITIVE_TO_ARRAY_OF.put(double.class, double[].class);
            PRIMITIVE_TO_ARRAY_OF.put(float.class, float[].class);
            PRIMITIVE_TO_ARRAY_OF.put(boolean.class, boolean[].class);
            PRIMITIVE_TO_ARRAY_OF.put(char.class, char[].class);
        }
        
        private Object convertPrimitiveReturnType(Object value, Class<?> returnType)
        {
            Class<?> actualType = value.getClass();
            
            Class<?> autoBoxOfReturnType = PRIMITIVE_TO_AUTOBOXED.get(returnType);
            Class<?> arrayOfReturnType = PRIMITIVE_TO_ARRAY_OF.get(returnType);
            
            Object result;
            if(actualType.equals(autoBoxOfReturnType))
            {
                result = value;
            }
            else if(actualType.equals(arrayOfReturnType))
            {
                if(Array.getLength(value) != 1)
                {
                    throw new IncompatibleReturnException("Array of " + returnType.getCanonicalName() + " does not " +
                                "have exactly 1 value.");
                }
                
                result = Array.get(value, 0);
            }
            else
            {
                throw new IncompatibleReturnException("Required return type is incompatible with the type actually " +
                        "returned\n" +
                        "Required type: " + returnType.getCanonicalName() + "\n" +
                        "Returned type: " + actualType.getCanonicalName());
            }
            
            return result;
        }
    }
    
    private static class CustomFunctionInvocation implements MatlabProxy.MatlabThreadCallable<Object[]>, Serializable
    {
        private final InvocationInfo _functionInfo;
        private final Object[] _args;
        
        private CustomFunctionInvocation(InvocationInfo functionInfo, Object[] args)
        {
            _functionInfo = functionInfo;
            _args = args;
        }

        @Override
        public Object[] call(MatlabThreadProxy proxy) throws MatlabInvocationException
        {
            String initialDir = null;
            
            //If the function was specified as not being on MATLAB's path
            if(_functionInfo.containingDirectory != null)
            {
                //Initial directory before cding
                initialDir = (String) proxy.returningFeval("pwd", 1)[0];
                
                //No need to change directory
                if(initialDir.equals(_functionInfo.containingDirectory))
                {
                    initialDir = null;
                }
                //Change directory to where the function is located
                else
                {
                    proxy.feval("cd", _functionInfo.containingDirectory);
                }
            }
            
            String[] parameterNames = new String[0];
            String[] returnNames = new String[0];
            try
            {
                //Set all arguments as MATLAB variables and build a function call using those variables
                String functionStr = _functionInfo.name + "(";
                parameterNames = generateNames(proxy, "param_", _args.length);
                for(int i = 0; i < _args.length; i++)
                {
                    Object arg = _args[i];
                    String name = parameterNames[i];
                    
                    if(arg instanceof MatlabType.MatlabTypeSerializedSetter)
                    {
                        ((MatlabType.MatlabTypeSerializedSetter) arg).setInMatlab(proxy, name);
                    }
                    else
                    {
                        proxy.setVariable(name, arg);
                    }
                    
                    functionStr += name;
                    if(i != _args.length - 1)
                    {
                        functionStr += ", ";
                    }
                }
                functionStr += ");";
                
                //Return arguments
                if(_functionInfo.returnTypes.length != 0)
                {
                    returnNames = generateNames(proxy, "return_", _functionInfo.returnTypes.length);
                    String returnStr = "[";
                    for(int i = 0; i < returnNames.length; i++)
                    {
                        returnStr += returnNames[i];
                        
                        if(i != returnNames.length - 1)
                        {
                            returnStr += ", ";
                        }
                    }
                    returnStr += "]";
                    
                    functionStr = returnStr + " = " + functionStr;
                }
                
                //Invoke the function
                proxy.eval(functionStr);
                
                //Get the return values
                Object[] returnValues = new Object[_functionInfo.returnTypes.length];
                for(int i = 0; i < returnValues.length; i++)
                {
                    Class<?> returnType = _functionInfo.returnTypes[i];
                    String returnName = returnNames[i];
                    
                    if(MatlabType.class.isAssignableFrom(returnType))
                    {
                        MatlabTypeSerializedGetter getter =
                                MatlabType.newSerializedGetter((Class<? extends MatlabType>) returnType);
                        getter.getInMatlab(proxy, returnName);
                        returnValues[i] = getter;
                    }
                    else
                    {
                        returnValues[i] = proxy.getVariable(returnName);
                    }
                }
                
                return returnValues;
            }
            //Restore MATLAB's state to what it was before the function call happened
            finally
            {
                try
                {
                    //Clear all variables used
                    List<String> createdVariables = new ArrayList<String>();
                    createdVariables.addAll(Arrays.asList(parameterNames));
                    createdVariables.addAll(Arrays.asList(returnNames));
                    if(!createdVariables.isEmpty())
                    {
                        String variablesStr = "";
                        for(int i = 0; i < createdVariables.size(); i++)
                        {
                            variablesStr += createdVariables.get(i);

                            if(i != createdVariables.size() - 1)
                            {
                                variablesStr += " ";
                            }
                        }
                        proxy.eval("clear " + variablesStr);
                    }
                }
                finally
                {
                    //If necessary, change back to the directory MATLAB was in before the function was invoked
                    if(initialDir != null)
                    {
                        proxy.feval("cd", initialDir);
                    }
                }
            }
        }
        
        private String[] generateNames(MatlabThreadProxy proxy, String root, int amount)
                throws MatlabInvocationException
        {
            //Build set of currently taken names
            Set<String> takenNames = new HashSet<String>(Arrays.asList((String[]) proxy.returningEval("who", 1)[0]));
            
            //Generate names
            List<String> generatedNames = new ArrayList<String>();
            int genSequenence = 0;
            while(generatedNames.size() != amount)
            {
                String generatedName = root + genSequenence;
                while(takenNames.contains(generatedName))
                {
                    genSequenence++;
                    generatedName = root + genSequenence;
                }
                genSequenence++;
                generatedNames.add(generatedName);
            }
            
            return generatedNames.toArray(new String[generatedNames.size()]);
        }
    }
    
    private static class StandardFunctionInvocation implements MatlabProxy.MatlabThreadCallable<Object[]>, Serializable
    {
        private final InvocationInfo _functionInfo;
        private final Object[] _args;
        
        private StandardFunctionInvocation(InvocationInfo functionInfo, Object[] args)
        {
            _functionInfo = functionInfo;
            _args = args;
        }
        
        @Override
        public Object[] call(MatlabThreadProxy proxy) throws MatlabInvocationException
        {
            String initialDir = null;
            
            //If the function was specified as not being on MATLAB's path
            if(_functionInfo.containingDirectory != null)
            {
                //Initial directory before cding
                initialDir = (String) proxy.returningFeval("pwd", 1)[0];
                
                //No need to change directory
                if(initialDir.equals(_functionInfo.containingDirectory))
                {
                    initialDir = null;
                }
                //Change directory to where the function is located
                else
                {
                    proxy.feval("cd", _functionInfo.containingDirectory);
                }
            }
            
            //Invoke function
            try
            {
                return proxy.returningFeval(_functionInfo.name, _functionInfo.returnTypes.length, _args);
            }
            //If necessary, change back to the directory MATLAB was in before the function was invoked
            finally
            {
                if(initialDir != null)
                {
                    proxy.feval("cd", initialDir);
                }
            }
        }
    }
}