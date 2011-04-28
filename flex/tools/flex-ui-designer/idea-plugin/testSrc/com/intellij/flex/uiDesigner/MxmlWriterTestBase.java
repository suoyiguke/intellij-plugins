package com.intellij.flex.uiDesigner;

import com.intellij.flex.uiDesigner.io.StringRegistry;
import com.intellij.flex.uiDesigner.libraries.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Consumer;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static com.intellij.flex.uiDesigner.DesignerTestApplicationManager.SocketTestInputHandler;

abstract class MxmlWriterTestBase extends AppTestBase {
  protected TestClient client;
  protected Socket socket;
  protected SocketTestInputHandler socketInputHandler;
  
  private int passedCounter;
  protected List<Library> libraries;
  private LibrarySet librarySet;
  protected File appRootDir;
  
  protected String getRawProjectRoot() {
    return useRawProjectRoot() ? getTestPath() : null;
  }
  
  protected boolean useRawProjectRoot() {
    return false;
  }

  protected List<OriginalLibrary> getLibraries(Consumer<OriginalLibrary> initializer) {
    List<OriginalLibrary> libraries = new ArrayList<OriginalLibrary>(libs.size());
    final LibraryManager libraryManager = LibraryManager.getInstance();
    for (Pair<VirtualFile, VirtualFile> pair : libs) {
      libraries.add(libraryManager.createOriginalLibrary(pair.getFirst(), pair.getSecond(), initializer, pair.getSecond().getUserData(IS_USER_LIB) == null));
    }
    
    return libraries;
  }

  protected final void runAdl() throws Exception {
    DesignerTestApplicationManager testApplicationManager = DesignerTestApplicationManager.getInstance();

    appRootDir = testApplicationManager.getAppDir();
    socketInputHandler = testApplicationManager.socketInputHandler;
    socket = testApplicationManager.getSocket();
    client = (TestClient)Client.getInstance();

    final StringRegistry.StringWriter stringWriter = new StringRegistry.StringWriter();
    stringWriter.startChange();

    ProblemsHolder problemsHolder = new ProblemsHolder();
    libraries = new SwcDependenciesSorter(appRootDir).sort(getLibraries(new LibraryStyleInfoCollector(myProject, myModule, stringWriter,
                                                                                                      problemsHolder)),
                                                           myProject.getLocationHash(), getFlexVersion());
    assertEmpty(problemsHolder.getResultList());

    librarySet = new LibrarySet(myProject.getLocationHash(), ApplicationDomainCreationPolicy.ONE, libraries);
    client.getRegisteredProjects().add(new ProjectInfo(myProject, librarySet));
    client.openProject(myProject);
    client.registerLibrarySet(librarySet, stringWriter);
    if (!isRequireLocalStyleHolder()) {
      registerModule(new ModuleInfo(myModule), stringWriter);
    }
  }

  private void registerModule(ModuleInfo moduleInfo, StringRegistry.StringWriter stringWriter) throws IOException {
    client.registerModule(myProject, moduleInfo, new String[]{librarySet.getId()}, stringWriter);
  }

  protected void modifyModule(ModifiableRootModel model, VirtualFile rootDir) {
  }

  /**
   * standard impl in CodeInsightestCase is not suitable for us — in case of not null rawProjectRoot (we need test file in package), 
   * we don't need "FileUtil.copyDir(projectRoot, toDirIO);"
   * also, skip openEditorsAndActivateLast
   */
  protected VirtualFile configureByFiles(final VirtualFile rawProjectRoot, final VirtualFile... vFiles) throws Exception {
    myFile = null;
    myEditor = null;

    final File toDirIO = createTempDirectory();
    final VirtualFile toDir = getVirtualFile(toDirIO);

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        try {
          final ModuleRootManager rootManager = ModuleRootManager.getInstance(myModule);
          final ModifiableRootModel rootModel = rootManager.getModifiableModel();
          
          boolean rootSpecified = rawProjectRoot != null;
          int rawRootPathLength = rootSpecified ? rawProjectRoot.getPath().length() : -1;
          // auxiliary files should be copied first
          for (int i = vFiles.length - 1; i >= 0; i--) {
            VirtualFile vFile = vFiles[i];
            if (rootSpecified) {
              copyFilesFillingEditorInfos(rawProjectRoot, toDir, vFile.getPath().substring(rawRootPathLength));
            }
            else {
              copyFilesFillingEditorInfos(vFile.getParent(), toDir, vFile.getName());
            }
          }

          rootModel.addContentEntry(toDir).addSourceFolder(toDir, false);
          modifyModule(rootModel, toDir);
          doCommitModel(rootModel);
          sourceRootAdded(toDir);
        }
        catch (IOException e) {
          LOG.error(e);
        }
      }
    });

    runAdl();
    return toDir;
  }
  
  protected void testFiles(final VirtualFile... originalVFiles) throws Exception {
    testFiles(new MyTester(), originalVFiles.length, originalVFiles);
  }
  
  protected void testFiles(String[] files, String... auxiliaryFiles) throws Exception {
    VirtualFile[] originalVFiles = new VirtualFile[files.length + auxiliaryFiles.length];
    int i = 0;
    for (String file : files) {
      originalVFiles[i++] = getVFile(getTestPath() + "/" + file);
    }
    int auxiliaryBorder = i;
    for (String file : auxiliaryFiles) {
      originalVFiles[i++] = getVFile(getTestPath() + "/" + file);
    }
    
    testFiles(new MyTester(), auxiliaryBorder, originalVFiles);
  }
  
  protected void testFile(String... originalVFilePath) throws Exception {
    testFile(new MyTester(), originalVFilePath);
  }
  
  protected void testFile(Tester tester, String... originalPaths) throws Exception {
    VirtualFile[] originalVFiles = new VirtualFile[originalPaths.length];
    for (int i = 0, originalPathsLength = originalPaths.length; i < originalPathsLength; i++) {
      originalVFiles[i] = getVFile(getTestPath() + "/" + originalPaths[i]);
    }
    
    testFiles(tester, 1, originalVFiles);
  }
  
  protected void testFiles(final Tester tester, final int auxiliaryBorder, final VirtualFile... originalVFiles) throws Exception {
    VirtualFile[] testVFiles = configureByFiles(useRawProjectRoot() ? getVFile(getRawProjectRoot()) : null, originalVFiles).getChildren();
    collectLocalStyleHolders();

    for (int childrenLength = testVFiles.length, i = childrenLength - auxiliaryBorder; i < childrenLength; i++) {
      final VirtualFile file = testVFiles[i];
      if (!file.getName().endsWith(".mxml")) {
        continue;
      }
      
      final VirtualFile originalVFile = originalVFiles[childrenLength - i - 1];
      final XmlFile xmlFile = (XmlFile) myPsiManager.findFile(file);
      assert xmlFile != null;
      ApplicationManager.getApplication().executeOnPooledThread(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          tester.test(file, xmlFile, originalVFile);
          return null;
        }
      }).get(8888, TimeUnit.SECONDS);
      //}).get(8, TimeUnit.SECONDS);
    }
  }

  private void collectLocalStyleHolders() throws IOException {
    if (isRequireLocalStyleHolder()) {
      ModuleInfo moduleInfo = new ModuleInfo(myModule);
      final StringRegistry.StringWriter stringWriter = new StringRegistry.StringWriter();
      stringWriter.startChange();
      ProblemsHolder problemsHolder = new ProblemsHolder();
      ModuleInfoUtil.collectLocalStyleHolders(moduleInfo, getFlexVersion(), stringWriter, problemsHolder);
      assertEmpty(problemsHolder.getResultList());
      registerModule(moduleInfo, stringWriter);
    }
  }
  
  protected void assertResult(String documentName) throws IOException {
    if (socketInputHandler.isPassed()) {
      passedCounter++;
    }
    else {
      fail(documentName + '\n' + socketInputHandler.getAndClearFailedMessage());
    }
  }

  @Override
  protected void tearDown() throws Exception {
    System.out.print("\npassed " + passedCounter + " tests.\n");
    
    super.tearDown();
  }

  private class MyTester implements Tester {
    @Override
    public void test(VirtualFile file, XmlFile xmlFile, VirtualFile originalFile) throws Exception {
      String documentName = file.getNameWithoutExtension();
      System.out.print(documentName + '\n');
      client.openDocument(myModule, xmlFile);
      client.test(documentName, originalFile.getParent().getName());
      socketInputHandler.setExpectedErrorMessage(expectedErrorForDocument(documentName));
      socketInputHandler.waitResult();
      assertResult(documentName);
    }
  }

  protected String expectedErrorForDocument(String documentName) {
    return null;
  }
}

interface Tester {
  void test(VirtualFile file, XmlFile xmlFile, VirtualFile originalFile) throws Exception;
}