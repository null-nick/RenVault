#import <Cocoa/Cocoa.h>
#include <jni.h>

static JavaVM *javaVM;
static jclass javaMenuClass;
static jmethodID javaAction;

static void sendJavaAction(jint keyCode, jboolean shift) {
    JNIEnv *env;
    BOOL attached = (*javaVM)->GetEnv(javaVM, (void **)&env, JNI_VERSION_1_6) == JNI_OK;
    if (!attached && (*javaVM)->AttachCurrentThreadAsDaemon(javaVM, (void **)&env, NULL) != JNI_OK) return;
    (*env)->CallStaticVoidMethod(env, javaMenuClass, javaAction, keyCode, shift);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    if (!attached) (*javaVM)->DetachCurrentThread(javaVM);
}

@interface RVMacMenuBar : NSObject <NSMenuItemValidation>
@property NSMenu *editMenu;
@property NSArray<NSMenu *> *menus;
@property id editKeyMonitor;
@property NSDictionary<NSString *, NSString *> *titles;
- (instancetype)initWithTitles:(NSDictionary<NSString *, NSString *> *)titles;
- (void)updateTitles:(NSDictionary<NSString *, NSString *> *)titles;
@end

static RVMacMenuBar *applicationMenus;

@implementation RVMacMenuBar

- (NSMenu *)addMenu:(NSString *)titleKey {
    NSString *title = self.titles[titleKey];
    NSMenu *menu = [[NSMenu alloc] initWithTitle:title];
    NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:title action:nil keyEquivalent:@""];
    item.representedObject = titleKey;
    item.submenu = menu;
    [NSApp.mainMenu addItem:item];
    return menu;
}

- (NSMenuItem *)addItem:(NSString *)titleKey to:(NSMenu *)menu action:(SEL)action key:(NSString *)key {
    NSMenuItem *item = [menu addItemWithTitle:self.titles[titleKey] action:action keyEquivalent:key];
    item.representedObject = titleKey;
    return item;
}

- (void)addEdit:(NSString *)titleKey key:(NSString *)key code:(NSInteger)code shift:(BOOL)shift {
    NSMenuItem *item = [self addItem:titleKey to:self.editMenu action:@selector(edit:) key:key];
    item.target = self;
    item.tag = code | (shift ? 0x100 : 0);
    item.keyEquivalentModifierMask = NSEventModifierFlagCommand | (shift ? NSEventModifierFlagShift : 0);
}

- (instancetype)initWithTitles:(NSDictionary<NSString *, NSString *> *)titles {
    self = [super init];
    if (!self) return nil;
    self.titles = titles;

    NSMenu *file = [self addMenu:@"getMenuFile"];
    [self addItem:@"getTrayOpen" to:file action:@selector(openWindow:) key:@""].target = self;
    [file addItem:NSMenuItem.separatorItem];
    [self addItem:@"getMenuCloseWindow" to:file action:@selector(performClose:) key:@"w"];

    self.editMenu = [self addMenu:@"getMenuEdit"];
    [self addEdit:@"getMenuUndo" key:@"z" code:'Z' shift:NO];
    [self addEdit:@"getMenuRedo" key:@"z" code:'Z' shift:YES];
    [self.editMenu addItem:NSMenuItem.separatorItem];
    [self addEdit:@"getMenuCut" key:@"x" code:'X' shift:NO];
    [self addEdit:@"getMenuCopy" key:@"c" code:'C' shift:NO];
    [self addEdit:@"getMenuPaste" key:@"v" code:'V' shift:NO];
    [self addEdit:@"getMenuSelectAll" key:@"a" code:'A' shift:NO];

    NSMenu *view = [self addMenu:@"getMenuView"];
    NSMenuItem *fullScreen = [self addItem:@"getMenuFullScreen" to:view action:@selector(toggleFullScreen:) key:@"f"];
    fullScreen.keyEquivalentModifierMask = NSEventModifierFlagCommand | NSEventModifierFlagControl;

    NSMenu *windows = [self addMenu:@"getWindowMenu"];
    [self addItem:@"getWindowMinimize" to:windows action:@selector(performMiniaturize:) key:@"m"];
    [self addItem:@"getWindowZoom" to:windows action:@selector(performZoom:) key:@""];
    [windows addItem:NSMenuItem.separatorItem];
    [self addItem:@"getWindowBringAllToFront" to:windows action:@selector(arrangeInFront:) key:@""];
    // Register once: repeated registration duplicates AppKit's tiling actions.
    NSApp.windowsMenu = windows;

    NSMenu *help = [self addMenu:@"getMenuHelp"];
    [self addItem:@"getMenuProject" to:help action:@selector(openProject:) key:@""].target = self;
    [self addItem:@"getMenuReportIssue" to:help action:@selector(reportIssue:) key:@""].target = self;
    NSApp.helpMenu = help;
    self.menus = @[file, self.editMenu, view, windows, help];

    // AWT forwards key equivalents to Compose before AppKit handles menus.
    // Handle Edit shortcuts here so each reaches the focused field exactly once.
    __weak RVMacMenuBar *weakSelf = self;
    self.editKeyMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown handler:^NSEvent *(NSEvent *event) {
        if ((event.modifierFlags & NSEventModifierFlagCommand) && [weakSelf.editMenu performKeyEquivalent:event]) return nil;
        return event;
    }];
    return self;
}

- (void)updateTitles:(NSDictionary<NSString *, NSString *> *)titles {
    self.titles = titles;
    for (NSMenu *menu in self.menus) {
        NSMenuItem *parent = [NSApp.mainMenu itemAtIndex:[NSApp.mainMenu indexOfItemWithSubmenu:menu]];
        menu.title = parent.title = titles[parent.representedObject];
        for (NSMenuItem *item in menu.itemArray) {
            if ([item.representedObject isKindOfClass:NSString.class]) {
                NSString *title = titles[item.representedObject];
                if (title != nil) item.title = title;
            }
        }
    }
}

- (BOOL)validateMenuItem:(NSMenuItem *)item {
    return item.action != @selector(edit:) || NSApp.keyWindow != nil;
}

- (void)openWindow:(id)sender { sendJavaAction(0, JNI_FALSE); }

- (void)edit:(NSMenuItem *)sender {
    BOOL shift = (sender.tag & 0x100) != 0;
    jint key = sender.tag & 0xff;
    SEL action;
    switch (key) {
        case 'Z': action = shift ? @selector(redo:) : @selector(undo:); break;
        case 'X': action = @selector(cut:); break;
        case 'C': action = @selector(copy:); break;
        case 'V': action = @selector(paste:); break;
        default: action = @selector(selectAll:); break;
    }
    // Native text editors (for example in a file dialog) use the responder chain.
    // NSWindow also implements undo:, but its undo manager cannot edit Compose text.
    if ([NSApp.keyWindow.firstResponder isKindOfClass:NSText.class]) {
        [NSApp sendAction:action to:nil from:sender];
    } else {
        sendJavaAction(key, shift);
    }
}

- (void)openProject:(id)sender {
    [NSWorkspace.sharedWorkspace openURL:[NSURL URLWithString:@"https://github.com/Laky-64/RenVault"]];
}

- (void)reportIssue:(id)sender {
    [NSWorkspace.sharedWorkspace openURL:[NSURL URLWithString:@"https://github.com/Laky-64/RenVault/issues"]];
}

@end

JNIEXPORT void JNICALL Java_io_lauren_renvault_MacMenuBar_installNative(JNIEnv *env, jobject self, jobject strings) {
    @autoreleasepool {
        if (javaMenuClass == NULL) {
            jclass type = (*env)->GetObjectClass(env, self);
            javaAction = (*env)->GetStaticMethodID(env, type, "performAction", "(IZ)V");
            if (javaAction == NULL) return;
            (*env)->GetJavaVM(env, &javaVM);
            javaMenuClass = (*env)->NewGlobalRef(env, type);
            (*env)->DeleteLocalRef(env, type);
            if (javaMenuClass == NULL) return;
        }

        // Read the same localized RvStrings used by the Compose UI.
        const char *getters[] = {
            "getMenuFile", "getTrayOpen", "getMenuCloseWindow", "getMenuEdit",
            "getMenuUndo", "getMenuRedo", "getMenuCut", "getMenuCopy", "getMenuPaste", "getMenuSelectAll",
            "getMenuView", "getMenuFullScreen", "getWindowMenu", "getWindowMinimize", "getWindowZoom",
            "getWindowBringAllToFront", "getMenuHelp", "getMenuProject", "getMenuReportIssue",
        };
        NSMutableDictionary *titles = [NSMutableDictionary dictionary];
        jclass stringsClass = (*env)->GetObjectClass(env, strings);
        for (size_t i = 0; i < sizeof(getters) / sizeof(getters[0]); i++) {
            jmethodID getter = (*env)->GetMethodID(env, stringsClass, getters[i], "()Ljava/lang/String;");
            if (getter == NULL) return;
            jstring value = (*env)->CallObjectMethod(env, strings, getter);
            if ((*env)->ExceptionCheck(env)) return;
            if (value == NULL) return;
            const jchar *characters = (*env)->GetStringChars(env, value, NULL);
            if (characters == NULL) return;
            titles[@(getters[i])] = [[NSString alloc] initWithCharacters:characters length:(*env)->GetStringLength(env, value)];
            (*env)->ReleaseStringChars(env, value, characters);
            (*env)->DeleteLocalRef(env, value);
        }
        (*env)->DeleteLocalRef(env, stringsClass);

        // AWT's event thread is not AppKit's main thread.
        dispatch_async(dispatch_get_main_queue(), ^{
            if (applicationMenus == nil) applicationMenus = [[RVMacMenuBar alloc] initWithTitles:titles];
            [applicationMenus updateTitles:titles];
        });
    }
}

