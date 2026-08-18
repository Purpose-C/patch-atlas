package shop;

public class PluginLoader {

    public Object load(String className) throws Exception {
        return Class.forName(className).getDeclaredConstructor().newInstance();
    }
}
