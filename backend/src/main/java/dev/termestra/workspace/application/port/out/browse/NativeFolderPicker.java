package dev.termestra.workspace.application.port.out.browse;

public interface NativeFolderPicker {
    NativePickResult pick();

    record NativePickResult(boolean canceled, String error, String path, boolean supported) {
        public static NativePickResult selected(String path) {
            return new NativePickResult(false, null, path, true);
        }
        public static NativePickResult canceledSelection() {
            return new NativePickResult(true, null, null, true);
        }
        public static NativePickResult unsupported(String error) {
            return new NativePickResult(false, error, null, false);
        }
        public static NativePickResult failed(String error) {
            return new NativePickResult(false, error, null, true);
        }
    }
}
