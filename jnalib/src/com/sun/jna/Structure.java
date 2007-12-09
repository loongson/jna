/* This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p/>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.  
 */
package com.sun.jna;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Represents a native structure with a Java peer class.  When used as a 
 * function parameter or return value, this class corresponds to 
 * <code>struct*</code>.  When used as a field within another 
 * <code>Structure</code>, it corresponds to <code>struct</code>.  The 
 * tagging interfaces {@link ByReference} and {@link ByValue} may be used
 * to alter the default behavior.
 * <p>
 * See the <a href={@docRoot}/overview-summary.html>overview</a> for supported
 * type mappings. 
 * <p>
 * Structure alignment and type mappings are derived by default from the
 * enclosing interface definition (if any) by using
 * {@link Native#getStructureAlignment} and {@link Native#getTypeMapper}.
 * <p>
 * NOTE: Strings are used to represent native C strings because usage of 
 * <code>char *</code> is generally more common than <code>wchar_t *</code>.
 * <p>
 * NOTE: This class assumes that fields are returned in {@link Class#getFields}
 * in the same or reverse order as declared.  If your VM returns them in
 * no particular order, you're out of luck.
 *
 * @author  Todd Fast, todd.fast@sun.com
 * @author twall@users.sf.net
 */
public abstract class Structure {
    /** Tagging interface to indicate the value of an instance of the 
     * <code>Structure</code> type is to be used in function invocations rather 
     * than its address.  The default behavior is to treat 
     * <code>Structure</code> function parameters and return values as by 
     * reference, meaning the address of the structure is used.
     */
    protected interface ByValue { }
    /** Tagging interface to indicate the address of an instance of the 
     * Structure type is to be used within a <code>Structure</code> definition 
     * rather than nesting the full Structure contents.  The default behavior 
     * is to inline <code>Structure</code> fields.
     */
    protected interface ByReference { }
    
    private static class MemberOrder {
        public int first;
        public int middle;
        public int last;
    }
    
    private static final boolean REVERSE_FIELDS;
    static final boolean isPPC;
    static final boolean isSPARC; 
    
    
    static {
        // IBM and JRockit store fields in reverse order; check for it
        Field[] fields = MemberOrder.class.getFields();
        REVERSE_FIELDS = "last".equals(fields[0].getName());
        if (!"middle".equals(fields[1].getName())) {
            throw new Error("This VM does not store fields in a predictable order");
        }
        String arch = System.getProperty("os.arch").toLowerCase();
        isPPC = "ppc".equals(arch) || "powerpc".equals(arch);
        isSPARC = "sparc".equals(arch);
    }

    /** Use the platform default alignment. */
    public static final int ALIGN_DEFAULT = 0;
    /** No alignment, place all fields on nearest 1-byte boundary */
    public static final int ALIGN_NONE = 1;
    /** validated for 32-bit x86 linux/gcc; align field size, max 4 bytes */
    public static final int ALIGN_GNUC = 2;
    /** validated for w32/msvc; align on field size */
    public static final int ALIGN_MSVC = 3;

    private static final int MAX_GNUC_ALIGNMENT = isSPARC ? 8 : NativeLong.SIZE;
    protected static final int CALCULATE_SIZE = -1;
    private static Map typeInfoMap = new WeakHashMap(); 
    // This field is accessed by native code
    private Pointer memory;
    private int size = CALCULATE_SIZE;
    private int alignType;
    private int structAlignment;
    private Map structFields = new LinkedHashMap();
    // Keep track of java strings which have been converted to C strings
    private Map nativeStrings = new HashMap();
    private TypeMapper typeMapper;
    // This field is accessed by native code
    private long typeInfo;

    protected Structure() {
        this(CALCULATE_SIZE);
    }

    protected Structure(int size) {
        this(size, ALIGN_DEFAULT);
    }

    protected Structure(int size, int alignment) {
        setAlignType(alignment);
        setTypeMapper(null);
        allocateMemory(size);
    }
    
    /** Return all fields in this structure (ordered). */
    Map fields() {
        return structFields;
    }

    /** Change the type mapping for this structure.  May cause the structure
     * to be resized and any existing memory to be reallocated.  
     * If <code>null</code>, the default mapper for the
     * defining class will be used.
     */
    protected void setTypeMapper(TypeMapper mapper) {
        if (mapper == null) {
            Class declaring = getClass().getDeclaringClass();
            if (declaring != null) {
                mapper = Native.getTypeMapper(declaring);
            }
        }
        this.typeMapper = mapper;
        this.size = CALCULATE_SIZE;
        this.memory = null;
    }
    
    /** Change the alignment of this structure.  Re-allocates memory if 
     * necessary.  If alignment is {@link #ALIGN_DEFAULT}, the default 
     * alignment for the defining class will be used. 
     */
    protected void setAlignType(int alignType) {
        if (alignType == ALIGN_DEFAULT) {
            Class declaring = getClass().getDeclaringClass();
            if (declaring != null) 
                alignType = Native.getStructureAlignment(declaring);
            if (alignType == ALIGN_DEFAULT) {
                if (Platform.isWindows())
                    alignType = ALIGN_MSVC;
                else
                    alignType = ALIGN_GNUC;
            }
        }
        this.alignType = alignType;
        this.size = CALCULATE_SIZE;
        this.memory = null;
    }

    /** Set the memory used by this structure.  This method is used to 
     * indicate the given structure is nested within another or otherwise
     * overlaid on some other memory block and thus does not own its own 
     * memory.
     */
    protected void useMemory(Pointer m) {
        useMemory(m, 0);
    }

    /** Set the memory used by this structure.  This method is used to 
     * indicate the given structure is nested within another or otherwise
     * overlaid on some other memory block and thus does not own its own 
     * memory.
     */
    protected void useMemory(Pointer m, int offset) {
        this.memory = m.share(offset, size());
    }
    
    /** Attempt to allocate memory if sufficient information is available.
     * Returns whether the operation was successful. 
     */
    protected void allocateMemory() {
        allocateMemory(calculateSize(true));
    }
    
    /** Provided for derived classes to indicate a different
     * size than the default.  Returns whether the operation was successful.
     */
    protected void allocateMemory(int size) {
        if (size == CALCULATE_SIZE) {
            // Analyze the struct, but don't worry if we can't yet do it
            size = calculateSize(false);
        }
        else if (size <= 0) {
            throw new IllegalArgumentException("Structure size must be greater than zero: " + size);
        }
        // May need to defer size calculation if derived class not fully
        // initialized
        if (size != CALCULATE_SIZE) {
            memory = new Memory(size);
            // Always clear new structure memory
            memory.clear(size);
            this.size = size;
            if (this instanceof ByValue) {
                typeInfo = getTypeInfo().peer;
            }
        }
    }

    public int size() {
        if (size == CALCULATE_SIZE) {
            // force allocation
            allocateMemory();
        }
        return size;
    }

    public void clear() {
        memory.clear(size());
    }

    /** Return a {@link Pointer} object to this structure.  Note that if you
     * use the structure's pointer as a function argument, you are responsible
     * for calling {@link #write()} prior to the call and {@link #read()} 
     * after the call.  These calls are normally handled automatically by the
     * {@link Function} object when it encounters a {@link Structure} argument
     * or return value.
     */
    public Pointer getPointer() {
        return memory;
    }

    //////////////////////////////////////////////////////////////////////////
    // Data synchronization methods
    //////////////////////////////////////////////////////////////////////////

    /**
     * Reads the fields of the struct from native memory
     */
    public void read() {
        // Read all fields
        for (Iterator i=structFields.values().iterator();i.hasNext();) {
            readField((StructField)i.next());
        }
    }

    /** Update the given field from native memory. */
    public void readField(String name) {
        StructField f = (StructField)structFields.get(name);
        if (f == null)
            throw new IllegalArgumentException("No such field: " + name);
        readField(f);
    }

    void readField(StructField structField) {
        
        // Get the offset of the field
        int offset = structField.offset;

        // Determine the type of the field
        Class nativeType = structField.type;
        FromNativeConverter readConverter = structField.readConverter;
        if (readConverter != null) {
            nativeType = readConverter.nativeType();
        }

        // Get the value at the offset according to its type
        Object result = null;
        if (Structure.class.isAssignableFrom(nativeType)) {
            Structure s = null;
            try {
                s = (Structure)structField.field.get(this);
                if (ByReference.class.isAssignableFrom(nativeType)) {
                    Pointer p = memory.getPointer(offset);
                    if (p == null) {
                        s = null;
                    }
                    else {
                        // Only preserve the field value if the pointer
                        // is unchanged
                        if (s == null || !p.equals(s.getPointer())) {
                            s = newInstance(nativeType);
                            s.useMemory(p);
                        }
                        s.read();
                    }
                }
                else {
                    s.useMemory(memory, offset);
                    s.read();
                }
            }
            catch (IllegalAccessException e) {
            }
            result = s;
        }
        else if (nativeType == byte.class || nativeType == Byte.class) {
            result = new Byte(memory.getByte(offset));
        }
        else if (nativeType == short.class || nativeType == Short.class) {
            result = new Short(memory.getShort(offset));
        }
        else if (nativeType == char.class || nativeType == Character.class) {
            result = new Character(memory.getChar(offset));
        }
        else if (nativeType == int.class || nativeType == Integer.class) {
            result = new Integer(memory.getInt(offset));
        }
        else if (nativeType == long.class || nativeType == Long.class) {
            result = new Long(memory.getLong(offset));
        }
        else if (nativeType == float.class || nativeType == Float.class) {
            result=new Float(memory.getFloat(offset));
        }
        else if (nativeType == double.class || nativeType == Double.class) {
            result = new Double(memory.getDouble(offset));
        }
        else if (nativeType == Pointer.class) {
            result = memory.getPointer(offset);
        }
        else if (nativeType == String.class) {
            Pointer p = memory.getPointer(offset);
            result = p != null ? p.getString(0) : null;
        }
        else if (nativeType == WString.class) {
            Pointer p = memory.getPointer(offset);
            result = p != null ? new WString(p.getString(0, true)) : null;
        }
        else if (Callback.class.isAssignableFrom(nativeType)) {
            // Overwrite the Java memory if the native pointer is a different
            // function pointer.
            Pointer fp = memory.getPointer(offset);
            if (fp == null) {
                result = null;
            }
            else try {
                Callback cb = (Callback)structField.field.get(this);
                Pointer oldfp = CallbackReference.getFunctionPointer(cb);
                if (!fp.equals(oldfp)) {
                    cb = CallbackReference.getCallback(nativeType, fp);
                }
                result = cb;
            }
            catch (IllegalArgumentException e) {
                // avoid overwriting Java field
                return;
            }
            catch (IllegalAccessException e) {
                // avoid overwriting Java field
                return;
            }
        }
        else if (nativeType.isArray()) {
            Class cls = nativeType.getComponentType();
            int length = 0;
            try {
                Object o = structField.field.get(this);
                if (o == null) {
                    throw new IllegalStateException("Array field in Structure not initialized");
                }
                length = Array.getLength(o);
                result = o;
            }
            catch (IllegalArgumentException e) {
            }
            catch (IllegalAccessException e) {
            }

            if (cls == byte.class) {
                memory.read(offset, (byte[])result, 0, length);
            }
            else if (cls == short.class) {
                memory.read(offset, (short[])result, 0, length);
            }
            else if (cls == char.class) {
                memory.read(offset, (char[])result, 0, length);
            }
            else if (cls == int.class) {
                memory.read(offset, (int[])result, 0, length);
            }
            else if (cls == long.class) {
                memory.read(offset, (long[])result, 0, length);
            }
            else if (cls == float.class) {
                memory.read(offset, (float[])result, 0, length);
            }
            else if (cls == double.class) {
                memory.read(offset, (double[])result, 0, length);
            }
            else if (Pointer.class.isAssignableFrom(cls)) {
                memory.read(offset, (Pointer[])result, 0, length);
            }
            else if (Structure.class.isAssignableFrom(cls)
                     && ByReference.class.isAssignableFrom(cls)) {
                Structure[] sarray = (Structure[])result;
                Pointer[] parray = memory.getPointerArray(offset, sarray.length);
                for (int i=0;i < sarray.length;i++) {
                    if (parray[i] == null) {
                        sarray[i] = null;
                    }
                    else {
                        if (sarray[i] == null 
                            || !parray[i].equals(sarray[i].getPointer())){
                            sarray[i] = newInstance(cls);
                            sarray[i].useMemory(parray[i]);
                        }
                        sarray[i].read();
                    }
                }
            }
            else {
                throw new IllegalArgumentException("Array of "
                                                   + cls + " not supported");
            }
        }
        else {
            throw new IllegalArgumentException("Unsupported field type \""
                                               + nativeType + "\"");
        }

        if (readConverter != null) {
            result = readConverter.fromNative(result, structField.context);
        }

        // Set the value on the field
        try {
            structField.field.set(this, result);
        }
        catch (Exception e) {
            throw new Error("Exception setting field \""
                            + structField.name+"\" to " + result 
                            + ": " + e, e);
        }
    }


    /**
     * Writes the fields of the struct to native memory
     */
    public void write() {
        // convenience: allocate memory if it hasn't been already; this
        // allows structures to inline arrays of primitive types and not have
        // to explicitly call allocateMemory in the ctor
        if (size == CALCULATE_SIZE) {
            allocateMemory();
        }
        // Write all fields, except those marked 'volatile'
        for (Iterator i=structFields.values().iterator();i.hasNext();) {
            StructField sf = (StructField)i.next();
            if (!sf.isVolatile) {
                writeField(sf);
            }
        }
    }
    
    /** Write the given field to native memory. */
    public void writeField(String name) {
        StructField f = (StructField)structFields.get(name);
        if (f == null)
            throw new IllegalArgumentException("No such field: " + name);
        writeField(f);
    }

    void writeField(StructField structField) {
        // Get the offset of the field
        int offset = structField.offset;

        // Get the value from the field
        Object value = null;
        try {
            value = structField.field.get(this);
        }
        catch (Exception e) {
            throw new Error("Exception reading field \""
                            + structField.name + "\"", e);
        }
        // Determine the type of the field
        Class nativeType = structField.type;
        ToNativeConverter converter = structField.writeConverter;
        if (converter != null) {
            value = converter.toNative(value, 
                    new StructureWriteContext(this, structField.field));
            // Assume any null values are pointers
            nativeType = value != null ? value.getClass() : Pointer.class;
        }

        // Java strings get converted to C strings, where a Pointer is used
        if (String.class == nativeType
            || WString.class == nativeType) {

            // Allocate a new string in memory
            boolean wide = nativeType == WString.class;
            if (value != null) {
                NativeString nativeString = new NativeString(value.toString(), wide);
                // Keep track of allocated C strings to avoid 
                // premature garbage collection of the memory.
                nativeStrings.put(structField.name, nativeString);
                value = nativeString.getPointer();
            }
            else {
                value = null;
            }
        }

        // Set the value at the offset according to its type
        if (nativeType == byte.class || nativeType == Byte.class) {
            memory.setByte(offset, ((Byte)value).byteValue());
        }
        else if (nativeType == short.class || nativeType == Short.class) {
            memory.setShort(offset, ((Short)value).shortValue());
        }
        else if (nativeType == char.class || nativeType == Character.class) {
            memory.setChar(offset, ((Character)value).charValue());
        }
        else if (nativeType == int.class || nativeType == Integer.class) {
            memory.setInt(offset, ((Integer)value).intValue());
        }
        else if (nativeType == long.class || nativeType == Long.class) {
            memory.setLong(offset, ((Long)value).longValue());
        }
        else if (nativeType == float.class || nativeType == Float.class) {
            memory.setFloat(offset, ((Float)value).floatValue());
        }
        else if (nativeType == double.class || nativeType == Double.class) {
            memory.setDouble(offset, ((Double)value).doubleValue());
        }
        else if (nativeType == Pointer.class) {
            memory.setPointer(offset, (Pointer)value);
        }
        else if (nativeType == String.class) {
            memory.setPointer(offset, (Pointer)value);
        }
        else if (nativeType == WString.class) {
            memory.setPointer(offset, (Pointer)value);
        }
        else if (nativeType.isArray()) {
            Class cls = nativeType.getComponentType();
            if (cls == byte.class) {
                byte[] buf = (byte[])value;
                memory.write(offset, buf, 0, buf.length);
            }
            else if (cls == short.class) {
                short[] buf = (short[])value;
                memory.write(offset, buf, 0, buf.length);
            }
            else if (cls == char.class) {
                char[] buf = (char[])value;
                memory.write(offset, buf, 0, buf.length);
            }
            else if (cls == int.class) {
                int[] buf = (int[])value;
                memory.write(offset, buf, 0, buf.length);
            }
            else if (cls == long.class) {
                long[] buf = (long[])value;
                memory.write(offset, buf, 0, buf.length);
            }
            else if (cls == float.class) {
                float[] buf = (float[])value;
                memory.write(offset, buf, 0, buf.length);
            }
            else if (cls == double.class) {
                double[] buf = (double[])value;
                memory.write(offset, buf, 0, buf.length);
            }
            else if (Pointer.class.isAssignableFrom(cls)) {
                Pointer[] buf = (Pointer[])value;
                memory.write(offset, buf, 0, buf.length);
            }
            else if (Structure.class.isAssignableFrom(cls)
                     && ByReference.class.isAssignableFrom(cls)) {
                Structure[] sbuf = (Structure[])value;
                Pointer[] buf = new Pointer[sbuf.length];
                for (int i=0;i < sbuf.length;i++) {
                    buf[i] = sbuf[i] == null ? null : sbuf[i].getPointer();
                }
                memory.write(offset, buf, 0, buf.length);
            }
            else {
                throw new IllegalArgumentException("Inline array of "
                                                   + cls + " not supported");
            }
        }
        else if (Structure.class.isAssignableFrom(nativeType)) {
            Structure s = (Structure)value;
            if (ByReference.class.isAssignableFrom(nativeType)) {
                if (s == null) {
                    memory.setPointer(offset, null);
                }
                else {
                    memory.setPointer(offset, s.getPointer());
                    s.write();
                }
            }
            else {
                s.useMemory(memory, offset);
                s.write();
            }
        }
        else if (Callback.class.isAssignableFrom(nativeType)) {
            memory.setPointer(offset, CallbackReference.getFunctionPointer((Callback)value));
        }
        else {
        	String msg = "Structure field \"" + structField.name
        	    + "\" was declared as " + nativeType 
        	    + ", which is not supported within a Structure";
            throw new IllegalArgumentException(msg);
        }
    }


    /** Calculate the amount of native memory required for this structure.
     * May return {@link #CALCULATE_SIZE} if the size can not yet be 
     * determined (usually due to fields in the derived class not yet
     * being initialized).
     * <p>
     * If the <code>force</code> parameter is <code>true</code> will throw
     * an {@link IllegalStateException} if the size can not be determined.
     * @throws IllegalStateException an array field is not initialized
     * @throws IllegalArgumentException when an unsupported field type is 
     * encountered
     */
    int calculateSize(boolean force) {
        // TODO: maybe cache this information on a per-class basis
        // so that we don't have to re-analyze this static information each 
        // time a struct is allocated.
		
        // Currently, we're not accounting for superclasses with declared
        // fields.  Since C structs have no inheritance, this shouldn't be
        // an issue.
        structAlignment = 1;
        int calculatedSize = 0;
        Field[] fields = getClass().getFields();
        if (REVERSE_FIELDS) {
            for (int i=0;i < fields.length/2;i++) {
                int idx = fields.length-1-i;
                Field tmp = fields[i];
                fields[i] = fields[idx];
                fields[idx] = tmp;
            }
        }
        for (int i=0; i<fields.length; i++) {
            Field field = fields[i];
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers))
                continue;
            
            Class type = field.getType();
            StructField structField = new StructField();
            structField.isVolatile = Modifier.isVolatile(modifiers);
            structField.field = field;
            structField.name = field.getName();
            structField.type = type;

            // Check for illegal field types
            if (Callback.class.isAssignableFrom(type) && !type.isInterface()) {
                throw new IllegalArgumentException("Structure Callback field '"
                                                   + field.getName() 
                                                   + "' must be an interface");
            }
            if (type.isArray() 
                && Structure.class.equals(type.getComponentType())) {
                String msg = "Nested Structure arrays must use a "
                    + "derived Structure type so that the size of "
                    + "the elements can be determined";
                throw new IllegalArgumentException(msg);
            }
            
            int fieldAlignment = 1;
            try {
                Object value = field.get(this);
                if (value == null) {
                    if (Structure.class.isAssignableFrom(type)
                        && !(ByReference.class.isAssignableFrom(type))) {
                        try {
                            value = newInstance(type);
                            field.set(this, value);
                        }
                        catch(IllegalArgumentException e) {
                            String msg = "Can't determine size of nested structure: " 
                                + e.getMessage();
                            throw new IllegalArgumentException(msg);
                        }
                    }
                    else if (type.isArray()) {
                        // can't calculate size yet, defer until later
                        if (force) {
                            throw new IllegalStateException("Array fields must be initialized");
                        }
                        return CALCULATE_SIZE;
                    }
                }
                Class nativeType = type;
                if (NativeMapped.class.isAssignableFrom(type)) {
                    NativeMappedConverter tc = new NativeMappedConverter(type);
                    value = tc.defaultValue();
                    nativeType = tc.nativeType();
                    structField.writeConverter = tc;
                    structField.readConverter = tc;
                    structField.context = new StructureReadContext(this, field);
                    field.set(this, value);
                }
                else if (typeMapper != null) {
                    ToNativeConverter writeConverter = typeMapper.getToNativeConverter(type);
                    FromNativeConverter readConverter = typeMapper.getFromNativeConverter(type);
                    if (writeConverter != null && readConverter != null) {
                        value = writeConverter.toNative(value,
                                new StructureWriteContext(this, structField.field));
                        nativeType = value != null ? value.getClass() : Pointer.class;
                        structField.writeConverter = writeConverter;
                        structField.readConverter = readConverter;
                        structField.context = new StructureReadContext(this, field);
                    }
                    else if (writeConverter != null || readConverter != null) {
                        String msg = "Structures require bidirectional type conversion for " + type;
                        throw new IllegalArgumentException(msg);
                    }
                }
                structField.size = getNativeSize(nativeType, value);
                fieldAlignment = getNativeAlignment(nativeType, value, i==0);
            }
            catch (IllegalAccessException e) {
                // ignore non-public fields
            }
            
            // Align fields as appropriate
            structAlignment = Math.max(structAlignment, fieldAlignment);
            if ((calculatedSize % fieldAlignment) != 0) {
                calculatedSize += fieldAlignment - (calculatedSize % fieldAlignment);
            }
            structField.offset = calculatedSize;
            calculatedSize += structField.size;
            
            // Save the field in our list
            structFields.put(structField.name, structField);
        }

        if (calculatedSize > 0) {
            return calculateAlignedSize(calculatedSize);
        }

        throw new IllegalArgumentException("Structure " + getClass()
                                           + " has unknown size (ensure "
                                           + "all fields are public)");
    }
    
    int calculateAlignedSize(int calculatedSize) {
        // Structure size must be an integral multiple of its alignment,
        // add padding if necessary.
        if (alignType != ALIGN_NONE) {
            if ((calculatedSize % structAlignment) != 0) {
                calculatedSize += structAlignment - (calculatedSize % structAlignment);
            }
        }
        return calculatedSize;
    }

    /** Overridable in subclasses. */
    // TODO: write getNaturalAlignment(stack/alloc) + getEmbeddedAlignment(structs)
    // TODO: move this into a native call which detects default alignment
    // automatically
    protected int getNativeAlignment(Class type, Object value, boolean firstElement) {
        int alignment = 1;
        int size = getNativeSize(type, value);
        if (type.isPrimitive() || Long.class == type || Integer.class == type
            || Short.class == type || Character.class == type 
            || Byte.class == type 
            || Float.class == type || Double.class == type) {
            alignment = size;
        }
        else if (Pointer.class == type
                 || Buffer.class.isAssignableFrom(type)
                 || Callback.class.isAssignableFrom(type)
                 || WString.class == type
                 || String.class == type) {
            alignment = Pointer.SIZE;
        }
        else if (Structure.class.isAssignableFrom(type)) {
            if (ByReference.class.isAssignableFrom(type)) {
                alignment = Pointer.SIZE;
            }
            else {
                alignment = ((Structure)value).structAlignment;
            }
        }
        else if (type.isArray()) {
            alignment = getNativeAlignment(type.getComponentType(), null, firstElement);
        }
        else {
            throw new IllegalArgumentException("Type " + type + " has unknown "
                                               + "native alignment");
        }
        if (alignType == ALIGN_NONE)
            return 1;
        if (alignType == ALIGN_MSVC)
            return Math.min(8, alignment);
        if (alignType == ALIGN_GNUC) {
            // NOTE this is published ABI for 32-bit gcc/linux/x86, osx/x86,
            // and osx/ppc.  osx/ppc special-cases the first element
            if (!firstElement || !isPPC)
                return Math.min(MAX_GNUC_ALIGNMENT, alignment);
        }
        return alignment;
    }

    /** Returns the native size for classes which don't need an object instance
     * to determine size.
     */
    protected int getNativeSize(Class cls) {
        if (cls == byte.class || cls == Byte.class) return 1;
        if (cls == short.class || cls == Short.class) return 2; 
        if (cls == char.class || cls == Character.class) return Native.WCHAR_SIZE;
        if (cls == int.class || cls == Integer.class) return 4;
        if (cls == long.class || cls == Long.class) return 8;
        if (cls == float.class || cls == Float.class) return 4;
        if (cls == double.class || cls == Double.class) return 8;
        if (Pointer.class == cls
            || Callback.class.isAssignableFrom(cls)
            || String.class == cls
            || WString.class == cls) {
            return Pointer.SIZE;
        }
        throw new IllegalArgumentException("The type \"" + cls.getName() 
        								   + "\" is not supported as a Structure field");
    }

    /** Returns the native size of the given class, in bytes. */
    protected int getNativeSize(Class type, Object value) {
        if (Structure.class.isAssignableFrom(type)) {
            if (ByReference.class.isAssignableFrom(type)) {
                return Pointer.SIZE;
            }
            else {
                Structure s = (Structure)value;
                // inline structure
                return s.size();
            }
        }
        if (type.isArray()) {
            int len = Array.getLength(value);
            if (len > 0) {
                Object o = Array.get(value, 0);
                return len * getNativeSize(type.getComponentType(), o);
            }
            // Don't process zero-length arrays
            throw new IllegalArgumentException("Arrays of length zero not allowed in structure: " + this);
        }
        return getNativeSize(type);
    }

    public String toString() {
        String LS = System.getProperty("line.separator");
        String name = getClass().getName() + "(" + getPointer() + ")";
        String contents = "";
        // Write all fields
        for (Iterator i=structFields.values().iterator();i.hasNext();) {
            contents += "  " + i.next();
            contents += LS;
        }
        byte[] buf = getPointer().getByteArray(0, size());
        final int BYTES_PER_ROW = 4;
        contents += "memory dump" + LS;
        for (int i=0;i < buf.length;i++) {
            if ((i % BYTES_PER_ROW) == 0) contents += "[";
            if (buf[i] >=0 && buf[i] < 16)
                contents += "0";
            contents += Integer.toHexString(buf[i] & 0xFF);
            if ((i % BYTES_PER_ROW) == BYTES_PER_ROW-1 && i < buf.length-1)
                contents += "]" + LS;
        }
        contents += "]";
        return name + LS + contents;
    }
    
    /** Returns a view of this structure's memory as an array of structures.
     * Note that this <code>Structure</code> must have a public, no-arg
     * constructor.  If the structure is currently using a {@link Memory}
     * backing, the memory will be resized to fit the entire array.
     */
    public Structure[] toArray(Structure[] array) {
        if (memory instanceof Memory) {
            // reallocate if necessary
            Memory m = (Memory)memory;
            int requiredSize = array.length * size();
            if (m.getSize() < requiredSize) {
                m = new Memory(requiredSize);
                m.clear();
                useMemory(m);
            }
        }
        array[0] = this;
        int size = size();
        for (int i=1;i < array.length;i++) {
            array[i] = Structure.newInstance(getClass());
            array[i].useMemory(memory.share(i*size, size));
            array[i].read();
        }
        return array;
    }
    
    /** Returns a view of this structure's memory as an array of structures.
     * Note that this <code>Structure</code> must have a public, no-arg
     * constructor.  If the structure is currently using a {@link Memory}
     * backing, the memory will be resized to fit the entire array.
     */
    public Structure[] toArray(int size) {
        return toArray((Structure[])Array.newInstance(getClass(), size));
    }

    /** This structure is only equal to another based on the same native 
     * memory address and data type.
     */
    public boolean equals(Object o) {
        return o == this
            || (o != null
                && o.getClass() == getClass()
                && ((Structure)o).getPointer().equals(getPointer()));
    }
    
    /** Since {@link #equals} depends on the native address, use that
     * as the hash code.
     */
    public int hashCode() {
        return getPointer().hashCode();
    }

    /** Returns field type information for this Structure. */
    private Pointer getTypeInfo() {
        synchronized(typeInfoMap) {
            Pointer info = (Pointer)typeInfoMap.get(getClass());
            if (info == null) {
                FFIType type = new FFIType(this);
                info = type.getPointer();
                typeInfoMap.put(getClass(), info);
            }
            return info;
        }
    }
    
    /** Create a new Structure instance of the given type
     * @param type
     * @return the new instance
     * @throws IllegalArgumentException if the instantiation fails
     */
    static Structure newInstance(Class type) throws IllegalArgumentException {
        try {
            Structure s = (Structure)type.newInstance();
            if (s instanceof ByValue) {
                s.allocateMemory();
            }
            return s;
        }
        catch(InstantiationException e) {
            String msg = "Can't instantiate " + type + " (" + e + ")";
            throw new IllegalArgumentException(msg);
        }
        catch(IllegalAccessException e) {
            String msg = "Instantiation of " + type 
                + " not allowed, is it public? (" + e + ")";
            throw new IllegalArgumentException(msg);
        }
    }

    class StructField extends Object {
        public String name;
        public Class type;
        public Field field;
        public int size = -1;
        public int offset = -1;
        public boolean isVolatile;
        public FromNativeConverter readConverter;
        public ToNativeConverter writeConverter;
        public FromNativeContext context;
        public String toString() {
            Object value = "<unavailable>";
            try {
                value = field.get(Structure.this);
            }
            catch(Exception e) { }
            return type + " " + name + "@" + Integer.toHexString(offset) 
                + "=" + value;
        }
    }

    /** This class auto-generates an ffi_type structure appropriate for a given
     * structure.  It fills in the type info, which gets replaced in the native
     * layer with actual pointers to ffi_type structures.
     */
    private static class FFIType extends Structure {
        public static class size_t extends IntegerType {
            public size_t() { this(0); }
            public size_t(long v) { super(Pointer.SIZE, v); }
        }

        private static final int FFI_TYPE_VOID = 0;
        private static final int FFI_TYPE_INT = 1;
        private static final int FFI_TYPE_FLOAT = 2;
        private static final int FFI_TYPE_DOUBLE = 3;
        private static final int FFI_TYPE_LONGDOUBLE = 4;
        private static final int FFI_TYPE_UINT8 = 5;
        private static final int FFI_TYPE_SINT8 = 6;
        private static final int FFI_TYPE_UINT16 = 6;
        private static final int FFI_TYPE_SINT16 = 8;
        private static final int FFI_TYPE_UINT32 = 9;
        private static final int FFI_TYPE_SINT32 = 10;
        private static final int FFI_TYPE_UINT64 = 11;
        private static final int FFI_TYPE_SINT64 = 12;
        private static final int FFI_TYPE_STRUCT = 13;
        private static final int FFI_TYPE_POINTER = 14;
        public size_t size;
        public short alignment;
        public short type = FFI_TYPE_VOID;
        public Pointer elements;
        public FFIType(Structure ref) {
            Pointer[] els = getFieldFFITypes(ref);
            init(els);
        }
        // Represent fixed-size arrays as structures of N identical elements
        private FFIType(Class type, int length) {
            Pointer[] els = new Pointer[length+1];
            Pointer p = getFFIType(type);
            for (int i=0;i < length;i++) {
                els[i] = p;
            }
            init(els);
        }
        private void init(Pointer[] els) {
            elements = new Memory(Pointer.SIZE * els.length);
            elements.write(0, els, 0, els.length);
            allocateMemory();
            write();
        }
        private Pointer getFFIType(Class cls) {
            return getFFIType(cls, -1);
        }
        private Pointer getFFIType(Class cls, int length) {
            long value = FFI_TYPE_VOID;
            if (cls == byte.class || cls == Byte.class) 
                value = FFI_TYPE_SINT8;
            else if (cls == short.class || cls == Short.class) 
                value = FFI_TYPE_SINT16; 
            else if (cls == char.class || cls == Character.class) 
                value = Native.WCHAR_SIZE == 2
                    ? FFI_TYPE_UINT16 : FFI_TYPE_UINT32;
            else if (cls == int.class || cls == Integer.class) 
                value = FFI_TYPE_SINT32;
            else if (cls == long.class || cls == Long.class) 
                value = FFI_TYPE_SINT64;
            else if (cls == float.class || cls == Float.class) 
                value = FFI_TYPE_FLOAT;
            else if (cls == double.class || cls == Double.class) 
                value = FFI_TYPE_DOUBLE;
            else if (Pointer.class == cls
                     || Buffer.class.isAssignableFrom(cls)
                     || Callback.class.isAssignableFrom(cls)
                     || String.class == cls
                     || WString.class == cls) {
                value = FFI_TYPE_POINTER;
            }
            else if (Structure.class.isAssignableFrom(cls)) {
                if (ByReference.class.isAssignableFrom(cls))
                    value = FFI_TYPE_POINTER;
                else
                    return newInstance(cls).getTypeInfo();
            }
            else if (cls.isArray()) {
                // Pretend it's a structure with length fields
                return new FFIType(cls.getComponentType(), length).getPointer();
            }
            else {
                throw new IllegalArgumentException("Unsupported structure field type " + cls);
            }
            return new Pointer(value);
        }
        private Pointer[] getFieldFFITypes(Structure s) {
            Pointer[] result = new Pointer[s.structFields.size() + 1];
            int idx = 0;
            for (Iterator i=s.structFields.values().iterator();i.hasNext();) {
                StructField sf = (StructField)i.next();
                int length = -1;
                if (sf.type.isArray()) {
                    try {
                        Object array = sf.field.get(s);
                        length = Array.getLength(array);
                    }
                    catch (IllegalArgumentException e) {
                    }
                    catch (IllegalAccessException e) {
                    }
                }
                result[idx++] = getFFIType(sf.type, length);
            }
            result[idx] = null;
            return result;
        }
    }
}
