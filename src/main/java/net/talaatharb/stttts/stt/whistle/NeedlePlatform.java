package net.talaatharb.stttts.stt.whistle;

import java.util.Locale;

/** Maps the running OS/CPU to the platform folder inside a Cactus runtime release on Hugging Face. */
public record NeedlePlatform(String folder, String executable) {

    public static NeedlePlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static NeedlePlatform detect(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = normalizeArch(osArch.toLowerCase(Locale.ROOT));
        if (os.contains("win")) {
            if (arch.equals("x86_64") || arch.equals("arm64")) {
                return new NeedlePlatform("windows-" + arch, "cactus.exe");
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (arch.equals("arm64")) {
                return new NeedlePlatform("macos-arm64", "cactus");
            }
        } else if (os.contains("linux")) {
            switch (arch) {
                case "x86_64":
                case "arm64":
                case "armv7":
                case "riscv64":
                    return new NeedlePlatform("linux-" + arch, "cactus");
                default:
                    break;
            }
        }
        throw new UnsupportedOperationException(
                "The Whistle engine has no prebuilt binary for " + osName + " / " + osArch);
    }

    private static String normalizeArch(String arch) {
        switch (arch) {
            case "amd64":
            case "x86_64":
            case "x64":
                return "x86_64";
            case "aarch64":
            case "arm64":
                return "arm64";
            case "arm":
            case "armv7l":
            case "armv7":
                return "armv7";
            case "riscv64":
                return "riscv64";
            default:
                return arch;
        }
    }

    public String remotePath() {
        return folder + "/" + executable;
    }
}
