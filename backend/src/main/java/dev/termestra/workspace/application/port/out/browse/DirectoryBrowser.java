package dev.termestra.workspace.application.port.out.browse;
import dev.termestra.workspace.application.port.in.browse.*;public interface DirectoryBrowser {BrowseView browse(String path);ProbeView probe(String path);}
