package tech.brettsaunders.craftory.persistence;

import com.google.gson.Gson;
import de.tr7zw.changeme.nbtapi.NBTCompound;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import lombok.NonNull;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import tech.brettsaunders.craftory.persistence.adapters.DataAdapter;
import tech.brettsaunders.craftory.persistence.adapters.HashMapAdapter;
import tech.brettsaunders.craftory.persistence.adapters.IntegerAdapter;
import tech.brettsaunders.craftory.persistence.adapters.ItemStackAdapter;
import tech.brettsaunders.craftory.persistence.adapters.LocationAdapter;
import tech.brettsaunders.craftory.persistence.adapters.LongAdapter;
import tech.brettsaunders.craftory.persistence.adapters.StringAdapter;
import tech.brettsaunders.craftory.utils.Logger;
import tech.brettsaunders.craftory.utils.ReflectionUtils;

public class PersistenceStorage {

    private Gson gson;
    private Map<Class<?>, DataAdapter<?>> converters;
    private Map<Class<?>, DataAdapter<?>> interfaceConverters;

    public PersistenceStorage() {
        gson = new Gson();
        converters = new HashMap<>();

        // Register default converters
        registerDataConverter(String.class, new StringAdapter(), false);
        registerDataConverter(Integer.class, new IntegerAdapter(), false);
        registerDataConverter(Long.class, new LongAdapter(), false);
        registerDataConverter(HashMap.class, new HashMapAdapter(), false);
        registerDataConverter(Location.class, new LocationAdapter(), false);

        interfaceConverters = new HashMap<>();
        registerInterfaceConverter(ItemStack.class, new ItemStackAdapter(), false);
    }

    public <T> void registerInterfaceConverter(@NonNull Class<T> clazz, @NonNull DataAdapter<? extends T> converter,
                                               boolean replace) {
        if (replace) {
            interfaceConverters.put(clazz, converter);
        } else {
            interfaceConverters.putIfAbsent(clazz, converter);
        }
    }

    public <T> void registerDataConverter(@NonNull Class<T> clazz, @NonNull DataAdapter<? extends T> converter,
                                          boolean replace) {
        if (replace) {
            converters.put(clazz, converter);
        } else {
            converters.putIfAbsent(clazz, converter);
        }
    }

    public void saveFields(@NonNull Object object, @NonNull NBTCompound nbtCompound) {
        ReflectionUtils.getFieldsRecursively(object.getClass(), Object.class).stream()
                .filter(field -> field.getAnnotation(Persistent.class) != null).forEach(field -> {
            field.setAccessible(true);
            try {
                if (field.get(object) != null) {
                    saveObject(field.get(object), nbtCompound.addCompound(field.getName()));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Unable to save field " + object.getClass().getSimpleName() + "." + field.getName(), e);
            }
        });
    }

    public void loadFields(@NonNull Object object, @NonNull NBTCompound nbtCompound) {
        loadFieldsDataObject(object, object, nbtCompound);
    }

    public void loadFieldsDataObject(@NonNull Object parent, @NonNull Object object, @NonNull NBTCompound nbtCompound) {
        ReflectionUtils.getFieldsRecursively(object.getClass(), Object.class).stream()
                .filter(field -> field.getAnnotation(Persistent.class) != null).forEach(field -> {
            field.setAccessible(true);
            if (nbtCompound.hasKey(field.getName())) {
                try {
                    Object obj = loadObject(parent, field.getType(), nbtCompound.getCompound(field.getName()));
                    if (obj != null || !(field.getType() == int.class || field.getType() == float.class || field.getType() == double.class)) {
                        field.set(object, obj);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "Unable to load field " + object.getClass().getSimpleName() + "." + field.getName(), e);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    public Class<?> saveObject(@NonNull Object data, @NonNull NBTCompound nbtCompound) {
        Class<?> clazz = data.getClass();

        // Check custom converters
        if (converters.containsKey(data.getClass())) {
            ((DataAdapter<Object>) converters.get(clazz)).store(this, data, nbtCompound);
            return clazz;
        }

        for (Entry<Class<?>, DataAdapter<?>> entry : interfaceConverters.entrySet()) {
            if (entry.getKey().isInstance(data)) {
                ((DataAdapter<Object>) entry.getValue()).store(this, data, nbtCompound);
                return entry.getKey();
            }
        }

        // Fallback to Json
        Logger.warn("Did not find a Wrapper for " + data.getClass().getName() + "! Falling back to Gson!");
        nbtCompound.setString("json", gson.toJson(data));
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T> T loadObject(@NonNull Object parent, @NonNull Class<T> type, @NonNull NBTCompound nbtCompound) {
        if (converters.containsKey(type)) {
            return (T) converters.get(type).parse(this, parent, nbtCompound);
        }

        for (Entry<Class<?>, DataAdapter<?>> entry : interfaceConverters.entrySet()) {
            if (entry.getKey().isAssignableFrom(type)) {
                return (T) entry.getValue().parse(this, parent, nbtCompound);
            }
        }

        // Fallback to Json
        if (nbtCompound.hasKey("json")) {
            return gson.fromJson(nbtCompound.getString("json"), type);
        }

        return null;
    }
}
