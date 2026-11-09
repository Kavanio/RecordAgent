fn main() {
  println!("cargo:rerun-if-changed=icons");
  println!("cargo:rerun-if-changed=tauri.conf.json");
  #[cfg(target_os = "macos")]
  build_macos_dev_notifier();
  tauri_build::build()
}

#[cfg(target_os = "macos")]
fn build_macos_dev_notifier() {
  if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("macos") {
    return;
  }
  let out_dir = std::path::PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR"));
  let src_path = out_dir.join("butvan_macos_notifier.m");
  let bin_path = out_dir.join("butvan-macos-notifier");
  let objc_source = r#"#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <UserNotifications/UserNotifications.h>

@interface ButvanFGDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation ButvanFGDelegate
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions options))completionHandler {
    completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList | UNNotificationPresentationOptionSound);
}
@end

static void activateRunningHostApp(void) {
    pid_t selfPid = [[NSProcessInfo processInfo] processIdentifier];
    for (NSRunningApplication *app in [[NSWorkspace sharedWorkspace] runningApplications]) {
        if (app.processIdentifier == selfPid) {
            continue;
        }
        NSString *exeName = app.executableURL.lastPathComponent ?: @"";
        NSString *name = app.localizedName ?: @"";
        if ([app.bundleIdentifier isEqualToString:@"com.butvan.agent"] ||
            [exeName isEqualToString:@"RecordAgent"] ||
            [exeName isEqualToString:@"ButvanAgent"] ||
            [name isEqualToString:@"ButvanAgent"] ||
            [name isEqualToString:@"Butvan Agent"]) {
            #pragma clang diagnostic push
            #pragma clang diagnostic ignored "-Wdeprecated-declarations"
            [app activateWithOptions:(1 << 1)];
            #pragma clang diagnostic pop
            break;
        }
    }
}

int main(int argc, const char * argv[]) {
    @autoreleasepool {
        if (argc < 3) {
            activateRunningHostApp();
            return 0;
        }
        NSString *title = [NSString stringWithUTF8String:argv[1]] ?: @"ButvanAgent";
        NSString *body = [NSString stringWithUTF8String:argv[2]] ?: @"";
        BOOL playSound = (argc >= 4 && strcmp(argv[3], "1") == 0);

        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        ButvanFGDelegate *delegate = [[ButvanFGDelegate alloc] init];
        [center setDelegate:delegate];

        __block BOOL finished = NO;
        __block BOOL success = NO;
        [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                              completionHandler:^(BOOL granted, NSError * _Nullable authError) {
            if (!granted || authError != nil) {
                finished = YES;
                return;
            }
            UNMutableNotificationContent *content = [[UNMutableNotificationContent alloc] init];
            content.title = title;
            content.body = body;
            if (playSound) {
                content.sound = [UNNotificationSound defaultSound];
            }
            NSString *identifier = [[NSUUID UUID] UUIDString];
            UNNotificationRequest *request = [UNNotificationRequest requestWithIdentifier:identifier
                                                                                  content:content
                                                                                  trigger:nil];
            [center addNotificationRequest:request withCompletionHandler:^(NSError * _Nullable addError) {
                success = (addError == nil);
                finished = YES;
            }];
        }];

        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:3.0];
        while (!finished && [[NSDate date] compare:deadline] == NSOrderedAscending) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                     beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
        }
        return success ? 0 : 1;
    }
}
"#;
  std::fs::write(&src_path, objc_source).expect("write butvan_macos_notifier.m");
  let arch = match std::env::var("CARGO_CFG_TARGET_ARCH").as_deref() {
    Ok("aarch64") => "arm64",
    Ok("x86_64") => "x86_64",
    _ => "arm64",
  };
  let status = std::process::Command::new("/usr/bin/clang")
    .args([
      "-O2",
      "-arch",
      arch,
      "-framework",
      "Foundation",
      "-framework",
      "AppKit",
      "-framework",
      "UserNotifications",
    ])
    .arg(&src_path)
    .arg("-o")
    .arg(&bin_path)
    .status()
    .expect("invoke clang for butvan-macos-notifier");
  assert!(status.success(), "failed to compile butvan-macos-notifier");
}
