package dev.termestra.tasks.adapter.out.filesystem;
import dev.termestra.tasks.application.port.out.*;
import dev.termestra.tasks.application.port.in.TasksDocumentAccessFailure;
import dev.termestra.tasks.application.port.in.TasksDocumentTooLarge;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
public final class NioTasksFileWatcher implements TasksFileWatcher {
    private final WatchServiceFactory watchServices;

    public NioTasksFileWatcher(){this(()->FileSystems.getDefault().newWatchService());}
    NioTasksFileWatcher(WatchServiceFactory watchServices){this.watchServices=watchServices;}

    @Override public TasksWatchRegistration watch(Path workspace,Runnable changed){
        Path directory=workspace.resolve(".termestra");
        WatchService watch;
        try{watch=watchServices.open();}
        catch(IOException error){throw new IllegalStateException("Failed to create tasks watcher",error);}
        try{
            register(directory,watch);
            AtomicBoolean open=new AtomicBoolean(true);
            Thread thread=Thread.ofVirtual().name("termestra-tasks-watch-"+workspace.getFileName()).start(()->run(watch,directory,open,changed));
            return ()->{if(open.compareAndSet(true,false)){
                IOException closeFailure=null;
                try{watch.close();}catch(IOException error){closeFailure=error;}
                finally{thread.interrupt();}
                if(closeFailure!=null)throw new IllegalStateException("Failed to close tasks watcher",closeFailure);
            }};
        }catch(IOException error){
            closeAfterFailedRegistration(watch,error);
            throw new IllegalStateException("Failed to watch tasks directory: "+directory,error);
        }catch(RuntimeException error){
            closeAfterFailedRegistration(watch,error);
            throw error;
        }
    }

    private static void closeAfterFailedRegistration(WatchService watch,Throwable root){
        try{watch.close();}catch(IOException closeFailure){root.addSuppressed(closeFailure);}
    }
    private static void run(WatchService watch,Path directory,AtomicBoolean open,Runnable changed){
        while(open.get()){
            try{
                WatchKey key=watch.take();boolean tasksChanged=false;
                for(WatchEvent<?> event:key.pollEvents())if(event.context() instanceof Path path&&path.getFileName().toString().equals("tasks.md"))tasksChanged=true;
                if(tasksChanged)try{changed.run();}catch(TasksDocumentTooLarge|TasksDocumentAccessFailure recoverable){
                    // The next filesystem event retries the read. A transient invalid or unreadable
                    // document must not silently tear down the long-lived watch registration.
                }
                if(!key.reset()&&!reRegister(watch,directory,open,changed))return;
            }catch(ClosedWatchServiceException ignored){return;}
            catch(InterruptedException error){Thread.currentThread().interrupt();return;}
        }
    }
    private static boolean reRegister(WatchService watch,Path directory,AtomicBoolean open,Runnable changed)throws InterruptedException{
        while(open.get()){
            try{register(directory,watch);try{changed.run();}catch(TasksDocumentTooLarge|TasksDocumentAccessFailure recoverable){/* A later event retries. */}return true;}
            catch(NoSuchFileException missing){Thread.sleep(200);}
            catch(IOException unavailable){Thread.sleep(500);}
        }
        return false;
    }
    private static void register(Path directory,WatchService watch)throws IOException{
        if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)){
            throw new IOException("Tasks watch directory must be a real directory: "+directory);
        }
        directory.register(watch,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
    }

    @FunctionalInterface interface WatchServiceFactory{WatchService open()throws IOException;}
}
