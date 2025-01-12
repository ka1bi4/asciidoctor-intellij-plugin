package org.asciidoc.intellij.psi;

import com.intellij.codeInsight.completion.CompletionUtilCore;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.ide.lightEdit.LightEdit;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.impl.AbstractProjectViewPane;
import com.intellij.ide.projectView.impl.ProjectViewPane;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Iconable;
import com.intellij.openapi.util.Trinity;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileInfoManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.serviceContainer.AlreadyDisposedException;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.text.CharArrayUtil;
import org.asciidoc.intellij.AsciiDoc;
import org.asciidoc.intellij.AsciiDocLanguage;
import org.asciidoc.intellij.threading.AsciiDocProcessUtil;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.yaml.snakeyaml.error.YAMLException;

import javax.swing.*;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.asciidoc.intellij.psi.AsciiDocBlockIdStubElementType.BLOCK_ID_WITH_VAR;

public class AsciiDocUtil {
  public static final String FAMILY_EXAMPLE = "example";
  public static final String FAMILY_ATTACHMENT = "attachment";
  public static final String FAMILY_PARTIAL = "partial";
  public static final String FAMILY_IMAGE = "image";
  public static final String FAMILY_PAGE = "page";
  public static final String ANTORA_YML = "antora.yml";

  public static final Set<String> ANTORA_SUPPORTED = new HashSet<>(Arrays.asList(
    // standard asciidoctor
    "image", "include", "video", "audio", "xref", "xref-attr",
    // extensions
    "plantuml"
  ));

  public static final Pattern ATTRIBUTES = Pattern.compile("\\{([a-zA-Z0-9_]+[a-zA-Z0-9_-]*)}");
  public static final int MAX_DEPTH = 10;

  static List<AsciiDocBlockId> findIds(Project project, String key) {
    if (key.length() == 0) {
      return Collections.emptyList();
    }
    List<AsciiDocBlockId> result = null;
    final GlobalSearchScope scope = new AsciiDocSearchScope(project).restrictedByAsciiDocFileType();
    Collection<AsciiDocBlockId> asciiDocBlockIds = AsciiDocBlockIdKeyIndex.getInstance().get(key, project, scope);
    for (AsciiDocBlockId asciiDocBlockId : asciiDocBlockIds) {
      result = collectBlockId(result, asciiDocBlockId);
    }
    if (result == null) {
      // if no block IDs have been found, search for block IDs that have attribute that need to resolve
      asciiDocBlockIds = AsciiDocBlockIdKeyIndex.getInstance().get(BLOCK_ID_WITH_VAR, project, scope);
      for (AsciiDocBlockId asciiDocBlockId : asciiDocBlockIds) {
        String name = asciiDocBlockId.getName();
        if (!matchKeyWithName(name, key, project, new ArrayDeque<>())) {
          continue;
        }
        result = collectBlockId(result, asciiDocBlockId);
      }
    }
    return result != null ? result : Collections.emptyList();
  }

  @NotNull
  private static List<AsciiDocBlockId> collectBlockId(@Nullable List<AsciiDocBlockId> result, AsciiDocBlockId asciiDocBlockId) {
    if (result == null) {
      result = new ArrayList<>();
    }
    result.add(asciiDocBlockId);
    return result;
  }

  private static boolean matchKeyWithName(String name, String key, Project project, ArrayDeque<Trinity<String, String, String>> stack) {
    if (stack.size() > MAX_DEPTH) {
      return false;
    }
    if (name.equals(key)) {
      return true;
    }
    if (stack.stream().anyMatch(p -> p.getThird().equals(key))) {
      return false;
    }
    Matcher matcherName = ATTRIBUTES.matcher(name);
    int start = 0;
    while (matcherName.find(start)) {
      if (matcherName.start() > key.length()) {
        return false;
      }
      if (!Objects.equals(key.substring(0, matcherName.start()), name.substring(0, matcherName.start()))) {
        return false;
      }

      String attributeName = matcherName.group(1);
      Optional<Trinity<String, String, String>> alreadyInStack = stack.stream().filter(p -> p.getFirst().equals(attributeName)).findAny();
      if (alreadyInStack.isPresent()) {
        // ensure that all occurrences in the replacement get the same value
        stack.push(alreadyInStack.get());
        String newName = new StringBuilder(name).replace(matcherName.start(), matcherName.end(), alreadyInStack.get().getSecond()).toString();
        if (matchKeyWithName(newName, key, project, stack)) {
          return true;
        }
        stack.pop();
      } else {
        List<AsciiDocAttributeDeclaration> declarations = AsciiDocUtil.findAttributes(project, attributeName);
        Set<String> searched = new HashSet<>(declarations.size());
        for (AttributeDeclaration decl : declarations) {
          String value = decl.getAttributeValue();
          if (value == null) {
            continue;
          }
          if (searched.contains(value)) {
            continue;
          }
          // avoid replacements where new value contains the old attribute as placeholder
          if (value.contains("{" + decl.getAttributeName() + "}")) {
            continue;
          }
          searched.add(value);
          stack.push(new Trinity<>(attributeName, value, key));
          String newName = new StringBuilder(name).replace(matcherName.start(), matcherName.end(), value).toString();
          if (matchKeyWithName(newName, key, project, stack)) {
            return true;
          }
          stack.pop();
        }
        if (searched.size() > 0) {
          break;
        }
      }
      start = matcherName.end();
    }
    return false;
  }

  public static List<PsiElement> findIds(Project project, VirtualFile virtualFile, String key) {
    List<PsiElement> result = new ArrayList<>();
    List<LookupElementBuilder> items = new ArrayList<>();
    AsciiDocFile asciiDocFile = (AsciiDocFile) PsiManager.getInstance(project).findFile(virtualFile);
    if (asciiDocFile != null) {
      findBlockIds(items, asciiDocFile, 0);
      for (LookupElementBuilder item : items) {
        PsiElement element = item.getPsiElement();
        if (element instanceof AsciiDocSection) {
          if (((AsciiDocSection) element).matchesAutogeneratedId(key)) {
            result.add(element);
          }
        } else if (element != null && item.getAllLookupStrings().contains(key)) {
          result.add(element);
        }
      }
    }
    return result;
  }

  public static void findBlockIds(List<LookupElementBuilder> items, PsiElement element, int level) {
    if (level > 64) {
      // avoid endless recursion
      return;
    }
    Collection<AsciiDocBlockId> properties = PsiTreeUtil.findChildrenOfType(element, AsciiDocBlockId.class);
    for (AsciiDocBlockId blockId : properties) {
      final Icon icon = blockId.getParent().getIcon(Iconable.ICON_FLAG_READ_STATUS | Iconable.ICON_FLAG_VISIBILITY);
      items.add(FileInfoManager.getFileLookupItem(blockId, blockId.getName(), icon)
        .withTypeText(element.getContainingFile().getName(), true));
    }
    Collection<AsciiDocSection> sections = PsiTreeUtil.findChildrenOfType(element, AsciiDocSection.class);
    for (AsciiDocSection section : sections) {
      // element has an ID specified, therefore skip checking the autogenerated ID
      if (section.getBlockId() != null) {
        continue;
      }
      final Icon icon = section.getIcon(Iconable.ICON_FLAG_READ_STATUS | Iconable.ICON_FLAG_VISIBILITY);
      items.add(FileInfoManager.getFileLookupItem(section, section.getAutogeneratedId(), icon)
        .withTypeText(element.getContainingFile().getName(), true));
    }
    Collection<AsciiDocBlockMacro> includes = PsiTreeUtil.findChildrenOfType(element, AsciiDocBlockMacro.class);
    for (AsciiDocBlockMacro macro : includes) {
      if (!"include".equals(macro.getMacroName())) {
        continue;
      }
      List<PsiReference> references = Arrays.asList(macro.getReferences());
      Collections.reverse(references);
      for (PsiReference reference : references) {
        if (reference instanceof AsciiDocFileReference) {
          AsciiDocFileReference fileReference = (AsciiDocFileReference) reference;
          if (!fileReference.isFolder()) {
            PsiElement resolved = fileReference.resolve();
            if (resolved instanceof AsciiDocFile) {
              findBlockIds(items, resolved, level + 1);
            }
          }
          break;
        }
      }
    }
  }

  static List<AsciiDocBlockId> findIds(Project project) {
    List<AsciiDocBlockId> result = new ArrayList<>();
    Collection<String> keys = AsciiDocBlockIdKeyIndex.getInstance().getAllKeys(project);
    final GlobalSearchScope scope = new AsciiDocSearchScope(project).restrictedByAsciiDocFileType();
    for (String key : keys) {
      result.addAll(AsciiDocBlockIdKeyIndex.getInstance().get(key, project, scope));
    }
    return result;
  }

  public static List<AsciiDocAttributeDeclaration> findAttributes(Project project, String key) {
    return findAttributes(project, key, false);
  }

  public static List<AsciiDocAttributeDeclaration> findAttributes(Project project, String key, boolean onlyAntora) {
    if (DumbService.isDumb(project)) {
      return Collections.emptyList();
    }
    ProgressManager.checkCanceled();
    List<AsciiDocAttributeDeclaration> result = null;
    final GlobalSearchScope scope = new AsciiDocSearchScope(project).restrictedByAsciiDocFileType();
    Collection<AsciiDocAttributeDeclaration> asciiDocAttributeDeclarations = AsciiDocAttributeDeclarationKeyIndex.getInstance().get(key, project, scope);
    Map<VirtualFile, Boolean> cache = new HashMap<>();
    for (AsciiDocAttributeDeclaration asciiDocAttributeDeclaration : asciiDocAttributeDeclarations) {
      VirtualFile virtualFile = asciiDocAttributeDeclaration.getContainingFile().getVirtualFile();
      if (onlyAntora) {
        if (!cache.computeIfAbsent(virtualFile.getParent(), s -> findAntoraModuleDir(project, s) != null)) {
          continue;
        }
      }
      if (result == null) {
        result = new ArrayList<>();
      }
      result.add(asciiDocAttributeDeclaration);
    }
    return result != null ? result : Collections.emptyList();
  }

  static List<AsciiDocAttributeDeclaration> findAttributes(Project project) {
    return findAttributes(project, false);
  }

  static List<AsciiDocAttributeDeclaration> findAttributes(Project project, boolean onlyAntora) {
    List<AsciiDocAttributeDeclaration> result = new ArrayList<>();
    Collection<String> keys = AsciiDocAttributeDeclarationKeyIndex.getInstance().getAllKeys(project);
    final GlobalSearchScope scope = new AsciiDocSearchScope(project).restrictedByAsciiDocFileType();
    Map<VirtualFile, Boolean> cache = new HashMap<>();
    for (String key : keys) {
      Collection<AsciiDocAttributeDeclaration> asciiDocAttributeDeclarations = AsciiDocAttributeDeclarationKeyIndex.getInstance().get(key, project, scope);
      for (AsciiDocAttributeDeclaration asciiDocAttributeDeclaration : asciiDocAttributeDeclarations) {
        VirtualFile virtualFile = asciiDocAttributeDeclaration.getContainingFile().getVirtualFile();
        if (onlyAntora) {
          if (!cache.computeIfAbsent(virtualFile.getParent(), s -> findAntoraModuleDir(project, s) != null)) {
            continue;
          }
        }
        result.add(asciiDocAttributeDeclaration);
      }
    }
    return result;
  }

  static List<AttributeDeclaration> findAttributes(Project project, String key, PsiElement current) {
    List<AttributeDeclaration> result = new ArrayList<>();

    key = key.toLowerCase(Locale.US);

    if (key.equals("snippets")) {
      augmentList(result, AsciiDocUtil.findSpringRestDocSnippets(current), key);
    }

    if (key.equals("docname")) {
      String name = current.getContainingFile().getName();
      result.add(new AsciiDocAttributeDeclarationDummy(key, name.replaceAll("\\..*$", "")));
    }

    if (key.equals("docfilesuffix")) {
      String name = current.getContainingFile().getName();
      if (name.contains(".")) {
        result.add(new AsciiDocAttributeDeclarationDummy(key, name.replaceAll("^(.*)(\\..*)$", "$2")));
      }
    }

    if (key.equals("docfile")) {
      VirtualFile vf = current.getContainingFile().getVirtualFile();
      if (vf == null) {
        vf = current.getContainingFile().getOriginalFile().getVirtualFile();
      }
      augmentList(result, vf, key);
    }

    if (key.equals("docdir")) {
      VirtualFile vf = current.getContainingFile().getVirtualFile();
      if (vf == null) {
        vf = current.getContainingFile().getOriginalFile().getVirtualFile();
      }
      if (vf != null) {
        vf = vf.getParent();
      }
      augmentList(result, vf, key);
    }

    VirtualFile antoraModuleDir = AsciiDocUtil.findAntoraModuleDir(current);
    if (antoraModuleDir != null) {
      VirtualFile vf;
      vf = current.getContainingFile().getVirtualFile();
      if (vf == null) {
        // when running autocomplete, there is only an original file
        vf = current.getContainingFile().getOriginalFile().getVirtualFile();
      }
      if (vf != null && vf.getParent() != null && vf.getParent().getCanonicalPath() != null) {
        Map<String, String> antoraAttributes = AsciiDoc.populateAntoraAttributes(project, new File(vf.getParent().getCanonicalPath()), antoraModuleDir);
        String value = antoraAttributes.get(key);
        if (value != null) {
          result.add(new AsciiDocAttributeDeclarationDummy(key, value));
        }
      }
    }

    // ignore other declarations when we found a specific value
    if (result.size() == 0) {
      result.addAll(findAttributes(project, key, antoraModuleDir != null));
    }

    if (result.size() == 0) {
      if (key.equals("outfilesuffix")) {
        // if no-one defined it, it is most likely '.html'
        result.add(new AsciiDocAttributeDeclarationDummy(key, ".html"));
      }
    }

    return result;
  }

  public static Map<String, String> collectAntoraAttributes(PsiElement element) {
    VirtualFile antoraModuleDir = AsciiDocUtil.findAntoraModuleDir(element);
    if (antoraModuleDir != null) {
      return AsciiDoc.collectAntoraAttributes(antoraModuleDir);
    } else {
      return Collections.emptyMap();
    }
  }

  static List<AttributeDeclaration> findAttributes(Project project, PsiElement current) {

    VirtualFile antoraModuleDir = AsciiDocUtil.findAntoraModuleDir(current);

    List<AttributeDeclaration> result = new ArrayList<>(findAttributes(project, antoraModuleDir != null));

    augmentList(result, AsciiDocUtil.findSpringRestDocSnippets(current), "snippets");

    if (antoraModuleDir != null) {
      augmentList(result, AsciiDocUtil.findAntoraPartials(current), FAMILY_PARTIAL + "sdir");
      augmentList(result, AsciiDocUtil.findAntoraImagesDir(current), FAMILY_IMAGE + "sdir");
      augmentList(result, AsciiDocUtil.findAntoraAttachmentsDir(current), FAMILY_ATTACHMENT + "sdir");
      augmentList(result, AsciiDocUtil.findAntoraExamplesDir(current), FAMILY_EXAMPLE + "sdir");
      collectAntoraAttributes(current).forEach((k, v) -> result.add(new AsciiDocAttributeDeclarationDummy(k, v)));
    }

    augmentAsciidoctorconfigDir(result, project, current);

    String name = current.getContainingFile().getName();
    result.add(new AsciiDocAttributeDeclarationDummy("docname", name.replaceAll("\\..*$", "")));
    if (name.contains(".")) {
      result.add(new AsciiDocAttributeDeclarationDummy("docfilesuffix", name.replaceAll("^(.*)(\\..*)$", "$2")));
    }

    VirtualFile vf = current.getContainingFile().getVirtualFile();
    if (vf == null) {
      vf = current.getContainingFile().getOriginalFile().getVirtualFile();
    }
    augmentList(result, vf, "docfile");
    if (vf != null) {
      vf = vf.getParent();
      augmentList(result, vf, "docdir");
    }

    if (result.stream().noneMatch(attributeDeclaration -> attributeDeclaration.getAttributeName().equals("outfilesuffix"))) {
      result.add(new AsciiDocAttributeDeclarationDummy("outfilesuffix", ".html"));
    }

    return result;
  }

  private static void augmentAsciidoctorconfigDir(List<AttributeDeclaration> result, Project project, PsiElement current) {
    VirtualFile currentFile = current.getContainingFile().getOriginalFile().getVirtualFile();
    if (currentFile != null) {
      VirtualFile folder = currentFile.getParent();
      if (folder != null) {
        while (true) {
          for (String configName : new String[]{".asciidoctorconfig", ".asciidoctorconfig.adoc"}) {
            VirtualFile configFile = folder.findChild(configName);
            if (configFile != null) {
              result.add(new AsciiDocAttributeDeclarationDummy("asciidoctorconfigdir", folder.getCanonicalPath()));
              return;
            }
          }
          if (folder.getPath().equals(project.getBasePath())) {
            break;
          }
          folder = folder.getParent();
          if (folder == null) {
            break;
          }
        }
      }
    }
  }

  static void augmentList(List<AttributeDeclaration> list, VirtualFile file, String attributeName) {
    if (file != null) {
      String value = file.getPath();
      value = value.replaceAll("\\\\", "/");
      list.add(new AsciiDocAttributeDeclarationDummy(attributeName, value));
    }
  }

  @Nullable
  public static PsiElement getStatementAtCaret(@NotNull Editor editor, @NotNull PsiFile psiFile) {
    int caret = editor.getCaretModel().getOffset();

    final Document doc = editor.getDocument();
    CharSequence chars = doc.getCharsSequence();
    int offset = caret == 0 ? 0 : CharArrayUtil.shiftBackward(chars, caret - 1, " \t");
    if (offset < 0) {
      // happens if spaces and tabs at beginning of file
      offset = 0;
    }
    if (doc.getLineNumber(offset) < doc.getLineNumber(caret)) {
      offset = CharArrayUtil.shiftForward(chars, caret, " \t");
    }

    return psiFile.findElementAt(offset);
  }

  @NotNull
  public static AsciiDocFile createFileFromText(@NotNull Project project, @NotNull String text) {
    return (AsciiDocFile) PsiFileFactory.getInstance(project).createFileFromText("a.adoc", AsciiDocLanguage.INSTANCE, text);
  }

  @Nullable
  public static VirtualFile findAntoraPartials(@NotNull Project project, VirtualFile fileBaseDir) {
    VirtualFile dir = fileBaseDir;
    Collection<String> roots = getRoots(project);
    while (dir != null) {
      if (dir.getParent() != null && dir.getParent().getName().equals("modules") &&
        dir.getParent().getParent().findChild(ANTORA_YML) != null) {
        VirtualFile antoraPartials = dir.findChild(FAMILY_PARTIAL + "s");
        if (antoraPartials != null) {
          return antoraPartials;
        }
        VirtualFile antoraPages = dir.findChild(FAMILY_PAGE + "s");
        if (antoraPages != null) {
          VirtualFile antoraPagePartials = antoraPages.findChild("_" + FAMILY_PARTIAL + "s");
          if (antoraPagePartials != null) {
            return antoraPagePartials;
          }
        }
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
    }
    return null;
  }

  public static VirtualFile findAntoraAttachmentsDir(@NotNull Project project, VirtualFile fileBaseDir) {
    VirtualFile dir = fileBaseDir;
    Collection<String> roots = getRoots(project);
    while (dir != null) {
      if (dir.getParent() != null && dir.getParent().getName().equals("modules") &&
        dir.getParent().getParent().findChild(ANTORA_YML) != null) {
        VirtualFile assets = dir.findChild("assets");
        if (assets != null) {
          VirtualFile attachments = assets.findChild(FAMILY_ATTACHMENT + "s");
          if (attachments != null) {
            return attachments;
          }
        }
        VirtualFile attachments = dir.findChild(FAMILY_ATTACHMENT + "s");
        if (attachments != null) {
          return attachments;
        }
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
    }
    return null;
  }

  public static @Nullable VirtualFile findAntoraPagesDir(@NotNull Project project, VirtualFile fileBaseDir) {
    VirtualFile dir = fileBaseDir;
    Collection<String> roots = getRoots(project);
    while (dir != null) {
      if (dir.getParent() != null && dir.getParent().getName().equals("modules") &&
        dir.getParent().getParent().findChild(ANTORA_YML) != null) {
        VirtualFile pages = dir.findChild(FAMILY_PAGE + "s");
        if (pages != null) {
          return pages;
        }
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
    }
    return null;
  }

  public static VirtualFile findAntoraModuleDir(@NotNull Project project, VirtualFile fileBaseDir) {
    VirtualFile dir = fileBaseDir;

    Collection<String> roots = getRoots(project);

    while (dir != null) {
      if (dir.getParent() != null && dir.getParent().getName().equals("modules") &&
        dir.getParent().getParent().findChild(ANTORA_YML) != null) {
        return dir;
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
    }
    return null;
  }

  private static final Map<Project, TreeSet<String>> PROJECT_ROOTS = new WeakHashMap<>();
  private static final Map<Project, MessageBusConnection> PROJECT_CONNECTIONS = new HashMap<>();

  private static void cache(Project project, TreeSet<String> roots) {
    synchronized (PROJECT_ROOTS) {
      try {
        if (PROJECT_ROOTS.get(project) == null) {
          // Listen to any file modification in the project, so that we can clear the cache
          MessageBusConnection connection = project.getMessageBus().connect();
          PROJECT_CONNECTIONS.put(project, connection);
          connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
              if (project.isDisposed()) {
                clear(project);
                return;
              }
              Set<VirtualFile> roots = new HashSet<>();
              for (VFileEvent event : events) {
                try {
                  if (event.getFile() != null && event.getFile().isValid()) {
                    VirtualFile contentRootForFile = ProjectFileIndex.getInstance(project).getContentRootForFile(event.getFile());
                    if (contentRootForFile != null) {
                      roots.add(contentRootForFile);
                    }
                  }
                } catch (AlreadyDisposedException ignored) {
                  // might happen if file is in a module that has already been disposed
                }
              }
              if (roots.size() > 0) {
                addRoots(project, roots);
              }
            }
          });
        }
        // lazy cache cleanup
        PROJECT_ROOTS.keySet().removeIf(ComponentManager::isDisposed);
        PROJECT_CONNECTIONS.entrySet().removeIf(entry -> {
          if (entry.getKey().isDisposed()) {
            entry.getValue().disconnect();
            return true;
          }
          return false;
        });
        PROJECT_ROOTS.put(project, roots);
      } catch (AlreadyDisposedException ex) {
        // noop - project already disposed
      }
    }
  }

  @Nullable
  private static TreeSet<String> retrieve(Project project) {
    synchronized (PROJECT_ROOTS) {
      return PROJECT_ROOTS.get(project);
    }
  }

  private static void clear(Project project) {
    synchronized (PROJECT_ROOTS) {
      PROJECT_ROOTS.remove(project);
      MessageBusConnection messageBusConnection = PROJECT_CONNECTIONS.get(project);
      if (messageBusConnection != null) {
        messageBusConnection.disconnect();
        PROJECT_CONNECTIONS.remove(project);
      }
    }
  }

  private static void addRoots(Project project, Set<VirtualFile> contentRoots) {
    synchronized (PROJECT_ROOTS) {
      TreeSet<String> roots = PROJECT_ROOTS.get(project);
      if (roots != null) {
        for (VirtualFile contentRoot : contentRoots) {
          addRoot(roots, contentRoot);
        }
      }
    }
  }

  @NotNull
  public static Collection<String> getRoots(@NotNull Project project) {
    TreeSet<String> roots = retrieve(project);
    if (roots != null) {
      return Collections.unmodifiableCollection(roots);
    }
    roots = new TreeSet<>();
    if (project.isDisposed()) {
      // module manager will not work on already disposed projects, therefore return empty list
      return Collections.unmodifiableCollection(roots);
    }
    ProgressManager.checkCanceled();
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      ProgressManager.checkCanceled();
      for (VirtualFile contentRoot : ModuleRootManager.getInstance(module).getContentRoots()) {
        addRoot(roots, contentRoot);
      }
    }
    cache(project, roots);
    return Collections.unmodifiableCollection(roots);
  }

  @TestOnly
  protected static void addRoot(TreeSet<String> roots, VirtualFile root) {
    String rootPath = root.getPath();
    if (roots.contains(rootPath)) {
      return;
    }
    String lowerEntry = roots.lower(rootPath);
    if (lowerEntry != null && rootPath.startsWith(lowerEntry + "/")) {
      return;
    }
    String higherEntry = roots.higher(rootPath);
    if (higherEntry != null && higherEntry.startsWith(rootPath + "/")) {
      roots.remove(higherEntry);
    }
    roots.add(rootPath);
  }

  public static String findAntoraImagesDirRelative(@NotNull Project project, VirtualFile fileBaseDir) {
    VirtualFile dir = fileBaseDir;
    Collection<String> roots = getRoots(project);
    StringBuilder imagesDir = new StringBuilder();
    while (dir != null) {
      if (dir.getParent() != null && dir.getParent().getName().equals("modules") &&
        dir.getParent().getParent().findChild(ANTORA_YML) != null) {
        VirtualFile assets = dir.findChild("assets");
        if (assets != null) {
          VirtualFile images = assets.findChild(FAMILY_IMAGE + "s");
          if (images != null) {
            return imagesDir + "assets/" + FAMILY_IMAGE + "s";
          }
        }
        VirtualFile images = dir.findChild(FAMILY_IMAGE + "s");
        if (images != null) {
          return imagesDir + FAMILY_IMAGE + "s";
        }
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
      imagesDir.insert(0, "../");
    }
    return null;
  }

  public static String findAntoraAttachmentsDirRelative(@NotNull Project project, VirtualFile fileBaseDir) {
    VirtualFile dir = fileBaseDir;
    Collection<String> roots = getRoots(project);
    StringBuilder attachmentsDir = new StringBuilder();
    while (dir != null) {
      if (dir.getParent() != null && dir.getParent().getName().equals("modules") &&
        dir.getParent().getParent().findChild(ANTORA_YML) != null) {
        VirtualFile assets = dir.findChild("assets");
        if (assets != null) {
          VirtualFile attachments = assets.findChild(FAMILY_ATTACHMENT + "s");
          if (attachments != null) {
            return attachmentsDir + "assets/" + FAMILY_ATTACHMENT + "s";
          }
        }
        VirtualFile attachments = dir.findChild(FAMILY_ATTACHMENT + "s");
        if (attachments != null) {
          return attachmentsDir + FAMILY_ATTACHMENT + "s";
        }
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
      attachmentsDir.insert(0, "../");
    }
    return null;
  }

  public static VirtualFile findAntoraImagesDir(@NotNull Project project, VirtualFile fileBaseDir) {
    Collection<String> roots = getRoots(project);
    VirtualFile dir = fileBaseDir;
    while (dir != null) {
      if (dir.getParent() != null && dir.getParent().getName().equals("modules") &&
        dir.getParent().getParent().findChild(ANTORA_YML) != null) {
        VirtualFile assets = dir.findChild("assets");
        if (assets != null) {
          VirtualFile images = assets.findChild(FAMILY_IMAGE + "s");
          if (images != null) {
            return images;
          }
        }
        VirtualFile images = dir.findChild(FAMILY_IMAGE + "s");
        if (images != null) {
          return images;
        }
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
    }
    return null;
  }

  public static VirtualFile findAntoraExamplesDir(@NotNull Project project, VirtualFile fileBaseDir) {
    Collection<String> roots = getRoots(project);
    VirtualFile dir = fileBaseDir;
    while (dir != null) {
      if (dir.getParent() != null && dir.getParent().getName().equals("modules") &&
        dir.getParent().getParent().findChild(ANTORA_YML) != null) {
        VirtualFile examples = dir.findChild(FAMILY_EXAMPLE + "s");
        if (examples != null) {
          return examples;
        }
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
    }
    return null;
  }

  public static Collection<VirtualFile> findAntoraNavFiles(Project project, VirtualFile moduleDir) {
    if (moduleDir.getParent() == null || !moduleDir.getParent().getName().equals("modules")) {
      return Collections.emptyList();
    }
    VirtualFile antoraFile = moduleDir.getParent().getParent().findChild(ANTORA_YML);
    if (antoraFile == null) {
      return Collections.emptyList();
    }
    String myComponentName;
    String myComponentVersion;
    try {
      Map<String, Object> myAntora = AsciiDoc.readAntoraYaml(antoraFile);
      myComponentName = getAttributeAsString(myAntora, "name");
      myComponentVersion = getAttributeAsString(myAntora, "version");
    } catch (YAMLException ex) {
      return Collections.emptyList();
    }

    PsiFile[] files =
      FilenameIndex.getFilesByName(project, ANTORA_YML, new AsciiDocSearchScope(project));
    ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();
    Collection<VirtualFile> result = new HashSet<>();
    for (PsiFile file : files) {
      if (index.isInLibrary(file.getVirtualFile())
        || index.isExcluded(file.getVirtualFile())
        || index.isInLibraryClasses(file.getVirtualFile())
        || index.isInLibrarySource(file.getVirtualFile())) {
        continue;
      }
      Map<String, Object> antora;
      try {
        antora = AsciiDoc.readAntoraYaml(antoraFile);
      } catch (YAMLException ex) {
        continue;
      }

      if (!Objects.equals(myComponentName, getAttributeAsString(antora, "name"))) {
        continue;
      }
      if (!Objects.equals(myComponentVersion, getAttributeAsString(antora, "version"))) {
        continue;
      }
      Object nav = antora.get("nav");
      if (nav instanceof Collection) {
        for (Object item : (Collection<?>) nav) {
          VirtualFile fileByRelativePath = file.getVirtualFile().getParent().findFileByRelativePath(item.toString());
          if (fileByRelativePath != null) {
            result.add(fileByRelativePath);
          }
        }
      }
    }
    return result;
  }

  public static VirtualFile findSpringRestDocSnippets(@NotNull Project project, VirtualFile fileBaseDir) {
    Collection<String> roots = getRoots(project);
    VirtualFile dir = fileBaseDir;
    while (dir != null) {
      VirtualFile pom = dir.findChild("pom.xml");
      if (pom != null) {
        VirtualFile targetDir = dir.findChild("target");
        if (targetDir != null) {
          VirtualFile snippets = targetDir.findChild("generated-snippets");
          if (snippets != null) {
            return snippets;
          }
        }
      }
      VirtualFile buildGradle = dir.findChild("build.gradle");
      if (buildGradle != null) {
        VirtualFile buildDir = dir.findChild("build");
        if (buildDir != null) {
          VirtualFile snippets = buildDir.findChild("generated-snippets");
          if (snippets != null) {
            return snippets;
          }
        }
      }
      VirtualFile buildGradleKts = dir.findChild("build.gradle.kts");
      if (buildGradleKts != null) {
        VirtualFile buildDir = dir.findChild("build");
        if (buildDir != null) {
          VirtualFile snippets = buildDir.findChild("generated-snippets");
          if (snippets != null) {
            return snippets;
          }
        }
      }
      if (roots.contains(dir.getName())) {
        break;
      }
      dir = dir.getParent();
    }

    return null;
  }

  public static VirtualFile findSpringRestDocSnippets(PsiElement element) {
    VirtualFile springRestDocSnippets = null;
    VirtualFile vf;
    vf = element.getContainingFile().getVirtualFile();
    if (vf == null) {
      // when running autocomplete, there is only an original file
      vf = element.getContainingFile().getOriginalFile().getVirtualFile();
    }
    if (vf != null) {
      springRestDocSnippets = findSpringRestDocSnippets(element.getProject(), vf);
    }
    return springRestDocSnippets;
  }

  @Nullable
  public static VirtualFile findAntoraPartials(PsiElement element) {
    VirtualFile antoraPartials = null;
    VirtualFile vf;
    vf = element.getContainingFile().getVirtualFile();
    if (vf == null) {
      // when running autocomplete, there is only an original file
      vf = element.getContainingFile().getOriginalFile().getVirtualFile();
    }
    if (vf != null) {
      antoraPartials = findAntoraPartials(element.getProject(), vf);
    }
    return antoraPartials;
  }

  public static VirtualFile findAntoraImagesDir(PsiElement element) {
    VirtualFile antoraImagesDir = null;
    VirtualFile vf;
    vf = element.getContainingFile().getVirtualFile();
    if (vf == null) {
      // when running autocomplete, there is only an original file
      vf = element.getContainingFile().getOriginalFile().getVirtualFile();
    }
    if (vf != null) {
      antoraImagesDir = findAntoraImagesDir(element.getProject(), vf);
    }
    return antoraImagesDir;
  }

  public static VirtualFile findAntoraExamplesDir(PsiElement element) {
    VirtualFile antoraExamplesDir = null;
    VirtualFile vf;
    vf = element.getContainingFile().getVirtualFile();
    if (vf == null) {
      // when running autocomplete, there is only an original file
      vf = element.getContainingFile().getOriginalFile().getVirtualFile();
    }
    if (vf != null) {
      antoraExamplesDir = findAntoraExamplesDir(element.getProject(), vf);
    }
    return antoraExamplesDir;
  }

  public static VirtualFile findAntoraAttachmentsDir(PsiElement element) {
    VirtualFile antoraAttachmentsDir = null;
    VirtualFile vf;
    vf = element.getContainingFile().getVirtualFile();
    if (vf == null) {
      // when running autocomplete, there is only an original file
      vf = element.getContainingFile().getOriginalFile().getVirtualFile();
    }
    if (vf != null) {
      antoraAttachmentsDir = findAntoraAttachmentsDir(element.getProject(), vf);
    }
    return antoraAttachmentsDir;
  }

  public static @Nullable VirtualFile findAntoraPagesDir(PsiElement element) {
    VirtualFile antoraPagesDir = null;
    VirtualFile vf;
    vf = element.getContainingFile().getVirtualFile();
    if (vf == null) {
      // when running autocomplete, there is only an original file
      vf = element.getContainingFile().getOriginalFile().getVirtualFile();
    }
    if (vf != null) {
      antoraPagesDir = findAntoraPagesDir(element.getProject(), vf);
    }
    return antoraPagesDir;
  }

  public static VirtualFile findAntoraModuleDir(PsiElement element) {
    VirtualFile antoraModuleDir = null;
    VirtualFile vf = null;
    if (element instanceof PsiFile) {
      vf = ((PsiFile) element).getVirtualFile();
    } else if (element instanceof PsiDirectory) {
      vf = ((PsiDirectory) element).getVirtualFile();
    } else {
      if (element.getContainingFile() != null) {
        vf = element.getContainingFile().getVirtualFile();
        if (vf == null) {
          // when running autocomplete, there is only an original file
          vf = element.getContainingFile().getOriginalFile().getVirtualFile();
        }
      }
    }
    if (vf != null) {
      antoraModuleDir = findAntoraModuleDir(element.getProject(), vf);
    }
    return antoraModuleDir;
  }

  // can include attributes
  public static final Pattern ANTORA_PREFIX_AND_FAMILY_PATTERN = Pattern.compile("^[a-zA-Z0-9:._@{}-]*(" + CompletionUtilCore.DUMMY_IDENTIFIER + "[a-zA-Z0-9:._{}@-]*)?[$:@]");

  public static final Pattern URL_PREFIX_PATTERN = Pattern.compile("^((https?|file|ftp|irc)://|mailto:)");

  // can include attributes
  public static final Pattern ANTORA_PREFIX_PATTERN = Pattern.compile("^[a-zA-Z0-9:._{}@-]*(" + CompletionUtilCore.DUMMY_IDENTIFIER + "[a-zA-Z0-9:._{}@-]*)?[:@]");

  public static final Pattern ANTORA_FAMILY_PATTERN = Pattern.compile("^[a-z]*(" + CompletionUtilCore.DUMMY_IDENTIFIER + "[a-z]*)?[$]");

  @Language("RegExp")
  private static final String FAMILIES = "(" + FAMILY_EXAMPLE + "|" + FAMILY_ATTACHMENT + "|" + FAMILY_PARTIAL + "|" + FAMILY_IMAGE + "|" + FAMILY_PAGE + ")";

  // 2.0@
  public static final Pattern VERSION = Pattern.compile("^(?<version>[a-zA-Z0-9._-]*)@");
  // component:module:
  public static final Pattern COMPONENT_MODULE = Pattern.compile("^(?<component>[a-zA-Z0-9._-]*):(?<module>[a-zA-Z0-9._-]*):");
  // module:
  public static final Pattern MODULE = Pattern.compile("^(?<module>[a-zA-Z0-9._-]*):");
  // family$
  public static final Pattern FAMILY = Pattern.compile("^(?<family>" + FAMILIES + ")\\$");

  @Nullable
  public static String resolveAttributes(PsiElement element, String val) {
    Matcher matcher = ATTRIBUTES.matcher(val);
    while (matcher.find()) {
      String attributeName = matcher.group(1);
      List<AttributeDeclaration> declarations = AsciiDocUtil.findAttributes(element.getProject(), attributeName, element);
      Set<String> values = new HashSet<>();
      for (AttributeDeclaration declaration : declarations) {
        String value = declaration.getAttributeValue();
        if (values.size() == 0) {
          values.add(value);
        } else if (!values.contains(value)) {
          return null;
        }
      }
      if (values.size() == 1) {
        String attrVal = values.iterator().next();
        if (attrVal != null) {
          val = matcher.replaceFirst(Matcher.quoteReplacement(attrVal));
          matcher = ATTRIBUTES.matcher(val);
        }
      } else {
        return null;
      }
    }
    return val;
  }

  public static List<String> replaceAntoraPrefix(PsiElement myElement, String key, String defaultFamily) {
    VirtualFile antoraModuleDir = findAntoraModuleDir(myElement);
    if (antoraModuleDir != null) {
      return replaceAntoraPrefix(myElement.getProject(), antoraModuleDir, key, defaultFamily);
    } else {
      return Collections.singletonList(key);
    }
  }

  public static List<String> replaceAntoraPrefix(Project project, VirtualFile moduleDir, String originalKey, String defaultFamily) {
    Matcher urlMatcher = URL_PREFIX_PATTERN.matcher(originalKey);
    if (urlMatcher.find()) {
      return Collections.singletonList(originalKey);
    }
    if (moduleDir != null) {
      return AsciiDocProcessUtil.runInReadActionWithWriteActionPriority(() -> {
        String key = originalKey;
        String myModuleName = moduleDir.getName();
        VirtualFile antoraFile = moduleDir.getParent().getParent().findChild(ANTORA_YML);
        if (antoraFile == null) {
          return Collections.singletonList(originalKey);
        }
        Map<String, Object> antora;
        try {
          antora = AsciiDoc.readAntoraYaml(antoraFile);
        } catch (YAMLException ex) {
          return Collections.singletonList(originalKey);
        }
        String myComponentName = getAttributeAsString(antora, "name");
        String myComponentVersion = getAttributeAsString(antora, "version");

        String otherComponentVersion = null;
        String otherComponentName = null;
        String otherModuleName = null;
        String otherFamily = null;

        Matcher version = VERSION.matcher(key);
        if (version.find()) {
          otherComponentVersion = version.group("version");
          key = version.replaceFirst("");
        }

        Matcher componentModule = COMPONENT_MODULE.matcher(key);
        if (componentModule.find()) {
          otherComponentName = componentModule.group("component");
          otherModuleName = componentModule.group("module");
          key = componentModule.replaceFirst("");
        } else {
          Matcher module = MODULE.matcher(key);
          if (module.find()) {
            otherModuleName = module.group("module");
            key = module.replaceFirst("");
          }
        }
        Matcher family = FAMILY.matcher(key);
        if (family.find()) {
          otherFamily = family.group("family");
          key = family.replaceFirst("");
        } else {
          if (defaultFamily == null) {
            return Collections.singletonList(originalKey);
          }
        }

        if (otherFamily == null || otherFamily.length() == 0) {
          otherFamily = defaultFamily;
        }

        String backup = null;
        List<VirtualFile> otherDirs = getOtherAntoraModuleDir(project, moduleDir, myModuleName, myComponentName, myComponentVersion, otherComponentVersion, otherComponentName, otherModuleName);
        List<String> result = new ArrayList<>();
        for (VirtualFile otherDir : otherDirs) {
          VirtualFile target;
          switch (otherFamily) {
            case FAMILY_EXAMPLE:
              target = AsciiDocUtil.findAntoraExamplesDir(project, otherDir);
              break;
            case FAMILY_ATTACHMENT:
              target = AsciiDocUtil.findAntoraAttachmentsDir(project, otherDir);
              break;
            case FAMILY_PAGE:
              target = AsciiDocUtil.findAntoraPagesDir(project, otherDir);
              break;
            case FAMILY_PARTIAL:
              target = AsciiDocUtil.findAntoraPartials(project, otherDir);
              break;
            case FAMILY_IMAGE:
              target = AsciiDocUtil.findAntoraImagesDir(project, otherDir);
              break;
            default:
              continue;
          }
          if (target == null) {
            continue;
          }
          String newKey = key;
          if (newKey.length() != 0) {
            newKey = "/" + newKey;
          }
          String value = target.getPath();
          value = value.replaceAll("\\\\", "/");
          newKey = value + newKey;
          if (new File(newKey).exists()) {
            // if the file exists, add it in first place
            result.add(0, newKey);
          } else {
            backup = newKey;
          }
        }
        if (result.size() == 0 && Objects.equals(defaultFamily, "page")) {
          resolvePageAliases(project, key, myModuleName, myComponentName, myComponentVersion, result);
        }
        if (result.size() == 0) {
          if (backup != null) {
            result.add(backup);
          } else {
            result.add(originalKey);
          }
        }
        return result;
      });
    }
    return Collections.singletonList(originalKey);
  }

  @SuppressWarnings("StringSplitter")
  private static void resolvePageAliases(Project project, String key, String myModuleName, String myComponentName, String myComponentVersion, List<String> result) {
    List<AsciiDocAttributeDeclaration> declarations = AsciiDocUtil.findAttributes(project, "page-aliases", true);
    for (AttributeDeclaration decl : declarations) {
      String shortKey = AsciiDocFileReference.normalizeKeyForSearch(key);
      String value = decl.getAttributeValue();
      if (value == null) {
        continue;
      }
      if (!value.contains(shortKey)) {
        continue;
      }
      if (!(decl instanceof AsciiDocAttributeDeclarationImpl)) {
        continue;
      }
      AsciiDocAttributeDeclarationImpl declImpl = (AsciiDocAttributeDeclarationImpl) decl;
      Map<String, String> otherAttributes = AsciiDocUtil.collectAntoraAttributes(declImpl);
      for (String element : value.split(",")) {
        Map<String, String> elementAttributes = new HashMap<>(otherAttributes);
        String shortElement = AsciiDocFileReference.normalizeKeyForSearch(element.trim());
        if (!shortElement.contains(shortKey)) {
          continue;
        }
        AsciiDocFileReference.parseAntoraPrefix(element.trim(), elementAttributes);
        if (!Objects.equals(myComponentName, elementAttributes.get("page-component-name"))) {
          continue;
        }
        if (!Objects.equals(myComponentVersion, elementAttributes.get("page-component-version"))) {
          continue;
        }
        if (!Objects.equals(myModuleName, elementAttributes.get("page-module"))) {
          continue;
        }
        if (!shortElement.equals(shortKey)) {
          continue;
        }
        result.add(declImpl.getContainingFile().getVirtualFile().getCanonicalPath());
      }
    }
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static List<VirtualFile> getOtherAntoraModuleDir(Project project, VirtualFile moduleDir,
                                                           String myModuleName, String myComponentName, String myComponentVersion,
                                                           String otherComponentVersion, String otherComponentName, String otherModuleName) {
    if (project.isDisposed()) {
      // FilenameIndex.getFilesByName will otherwise log an error later
      throw new ProcessCanceledException();
    }
    boolean useLatest = false;
    AntoraVersionDescriptor latestVersion = null;
    if (otherComponentVersion == null && otherComponentName != null) {
      useLatest = true;
    } else if (otherComponentVersion == null) {
      otherComponentVersion = myComponentVersion;
    }
    List<VirtualFile> result = new ArrayList<>();
    if (otherModuleName != null && otherComponentName == null) {
      otherComponentName = myComponentName;
    }
    if (otherComponentName == null && otherModuleName == null) {
      otherComponentName = myComponentName;
      otherModuleName = myModuleName;
    }

    if (otherComponentName != null && !DumbService.isDumb(project)) {
      if (otherModuleName == null || otherModuleName.length() == 0) {
        otherModuleName = "ROOT";
      }
      PsiFile[] files =
        FilenameIndex.getFilesByName(project, ANTORA_YML, new AsciiDocSearchScope(project));
      // sort by path proximity
      Arrays.sort(files,
        Comparator.comparingInt(value -> countNumberOfSameStartingCharacters(value, moduleDir.getPath()) * -1));
      ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();
      for (PsiFile file : files) {
        if (index.isInLibrary(file.getVirtualFile())
          || index.isExcluded(file.getVirtualFile())
          || index.isInLibraryClasses(file.getVirtualFile())
          || index.isInLibrarySource(file.getVirtualFile())) {
          continue;
        }
        PsiDirectory parent = file.getParent();
        if (parent == null) {
          continue;
        }
        PsiDirectory antoraModulesDir = parent.findSubdirectory("modules");
        if (antoraModulesDir == null) {
          continue;
        }
        PsiDirectory antoraModule = antoraModulesDir.findSubdirectory(otherModuleName);
        if (antoraModule == null) {
          continue;
        }
        Map<String, Object> antora;
        try {
          antora = AsciiDoc.readAntoraYaml(file);
        } catch (YAMLException ex) {
          continue;
        }
        if (!Objects.equals(otherComponentName, getAttributeAsString(antora, "name"))) {
          continue;
        }
        if (!useLatest) {
          if (!Objects.equals(otherComponentVersion, getAttributeAsString(antora, "version"))) {
            continue;
          }
        } else {
          AntoraVersionDescriptor otherVersion = new AntoraVersionDescriptor(getAttributeAsString(antora, "version"), getAttributeAsString(antora, "prerelease"));
          if (latestVersion == null) {
            latestVersion = otherVersion;
          } else {
            int compareResult = latestVersion.compareTo(otherVersion);
            if (compareResult < 0) {
              result.clear();
              latestVersion = otherVersion;
            } else if (compareResult > 0) {
              continue;
            }
          }
        }
        result.add(antoraModule.getVirtualFile());
      }
    }
    return result;
  }

  @Nullable
  public static String getAttributeAsString(Map<String, Object> antora, String name) {
    Object value = antora.get(name);
    if (value == null) {
      return null;
    }
    return value.toString();
  }

  public static List<AntoraModule> collectPrefixes(Project project, VirtualFile moduleDir) {
    if (DumbService.isDumb(project)) {
      return Collections.emptyList();
    }
    return AsciiDocProcessUtil.runInReadActionWithWriteActionPriority(() -> {
      PsiFile[] files =
        FilenameIndex.getFilesByName(project, ANTORA_YML, new AsciiDocSearchScope(project));
      List<AntoraModule> result = new ArrayList<>();
      // sort by path proximity
      Arrays.sort(files,
        Comparator.comparingInt(value -> countNumberOfSameStartingCharacters(value, moduleDir.getPath()) * -1));
      ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();
      VirtualFile antoraFile = moduleDir.getParent().getParent().findChild(ANTORA_YML);
      if (antoraFile == null) {
        return result;
      }
      Map<String, Object> antora;
      try {
        antora = AsciiDoc.readAntoraYaml(antoraFile);
      } catch (YAMLException ex) {
        return result;
      }
      String myComponentName = getAttributeAsString(antora, "name");
      String myComponentVersion = getAttributeAsString(antora, "version");
      Map<String, String> componentTitles = new HashMap<>();
      for (PsiFile file : files) {
        if (index.isInLibrary(file.getVirtualFile())
          || index.isExcluded(file.getVirtualFile())
          || index.isInLibraryClasses(file.getVirtualFile())
          || index.isInLibrarySource(file.getVirtualFile())) {
          continue;
        }
        try {
          antora = AsciiDoc.readAntoraYaml(file);
        } catch (YAMLException ex) {
          continue;
        }
        String otherComponentName = getAttributeAsString(antora, "name");
        String otherComponentVersion = getAttributeAsString(antora, "version");
        String title = getAttributeAsString(antora, "title");
        if (title != null && componentTitles.get(otherComponentName) == null) {
          componentTitles.put(otherComponentName, title);
        }
        String versionPrefix = "";
        if (!Objects.equals(myComponentVersion, otherComponentVersion)) {
          versionPrefix = otherComponentVersion + "@";
        }
        VirtualFile md = file.getVirtualFile().getParent().findChild("modules");
        if (md != null) {
          VirtualFile[] modules = md.getChildren();
          for (VirtualFile module : modules) {
            if (MODULE.matcher(module.getName() + ":").matches()) {
              if (Objects.equals(myComponentName, otherComponentName)) {
                result.add(new AntoraModule(versionPrefix + module.getName() + ":", otherComponentName, module.getName(), title, module));
              }
              if (module.getName().equals("ROOT")) {
                result.add(new AntoraModule(versionPrefix + otherComponentName + "::", otherComponentName, module.getName(), title, module));
              }
              result.add(new AntoraModule(versionPrefix + otherComponentName + ":" + module.getName() + ":", otherComponentName, module.getName(), title, module));
            }
          }
        }
      }
      Set<String> entries = new HashSet<>();
      Iterator<AntoraModule> iterator = result.iterator();
      while (iterator.hasNext()) {
        AntoraModule antoraModule = iterator.next();
        if (entries.contains(antoraModule.getPrefix())) {
          iterator.remove();
          continue;
        }
        entries.add(antoraModule.getPrefix());
        // title might not have been included on all modules, populate other if it has been set on some
        if (antoraModule.getTitle() == null) {
          antoraModule.setTitle(componentTitles.get(antoraModule.getComponent()));
        }
      }
      return result;
    });
  }

  public static List<VirtualFile> resolvePrefix(Project project, VirtualFile moduleDir, String otherKey) {
    return AsciiDocProcessUtil.runInReadActionWithWriteActionPriority(() -> {
      String myModuleName = moduleDir.getName();
      VirtualFile antoraFile = moduleDir.getParent().getParent().findChild(ANTORA_YML);
      if (antoraFile == null) {
        return null;
      }
      Map<String, Object> antora;
      try {
        antora = AsciiDoc.readAntoraYaml(antoraFile);
      } catch (YAMLException ex) {
        return null;
      }
      String myComponentName = getAttributeAsString(antora, "name");
      String myComponentVersion = getAttributeAsString(antora, "version");

      String otherComponentName = null;
      String otherModuleName = null;
      String otherComponentVersion = null;

      String key = otherKey;
      Matcher version = VERSION.matcher(key);
      if (version.find()) {
        otherComponentVersion = version.group("version");
        key = version.replaceFirst("");
      }
      Matcher componentModule = COMPONENT_MODULE.matcher(key);
      if (componentModule.find()) {
        otherComponentName = componentModule.group("component");
        otherModuleName = componentModule.group("module");
      } else {
        Matcher module = MODULE.matcher(key);
        if (module.find()) {
          otherModuleName = module.group("module");
        }
      }

      return getOtherAntoraModuleDir(project, moduleDir, myModuleName, myComponentName, myComponentVersion, otherComponentVersion, otherComponentName, otherModuleName);
    });
  }

  private static int countNumberOfSameStartingCharacters(PsiFile value, String origin) {
    String path = value.getVirtualFile().getPath();
    int i = 0;
    for (; i < origin.length() && i < path.length(); ++i) {
      if (path.charAt(i) != origin.charAt(i)) {
        break;
      }
    }
    return i;
  }

  public static Collection<AsciiDocAttributeDeclaration> findPageAttributes(PsiFile file) {
    Collection<AsciiDocAttributeDeclaration> result = new ArrayList<>();
    findPageAttributes(file, 0, result);
    return result;
  }

  protected static boolean findPageAttributes(PsiFile file, int depth, Collection<AsciiDocAttributeDeclaration> result) {
    if (depth > 64) {
      return true;
    }
    return PsiTreeUtil.processElements(file, new PageAttributeProcessor(result, depth + 1));
  }

  public static void selectFileInProjectView(Project project, VirtualFile file) {
    if (!LightEdit.owns(project) && !project.isDisposed()) {
      ProjectView projectView = ProjectView.getInstance(project);
      // trying to select project view pane as this will most likely show the PDF
      // (others might filter it, and user might not know where file was created)
      AbstractProjectViewPane projectViewPaneById = projectView.getProjectViewPaneById(ProjectViewPane.ID);
      if (projectViewPaneById != null) {
        // seen in RD 2020.3.3: project view panel might not be available (or project had already been disposed?)
        // ProjectViewImpl#changeViewCB() will log an exception when that happens
        projectView.changeView(ProjectViewPane.ID);
      }
      // select file newly created/updated file in project view
      projectView.select(null, file, true);
    }
  }

}
