package dev.termestra.workspace.application.service.browse;
import dev.termestra.workspace.application.port.in.browse.*;import dev.termestra.workspace.application.port.out.browse.DirectoryBrowser;
public final class FilesystemBrowseService implements FilesystemBrowseUseCase {private final DirectoryBrowser browser;public FilesystemBrowseService(DirectoryBrowser browser){this.browser=browser;}@Override public BrowseView browse(String path){return browser.browse(path==null?"":path);}@Override public ProbeView probe(String path){return browser.probe(path==null?"":path);}}
