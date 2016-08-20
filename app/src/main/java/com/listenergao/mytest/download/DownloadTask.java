package com.listenergao.mytest.download;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Created by ListenerGao on 2016/8/20.
 *
 * 下载任务类
 */
public class DownloadTask {
    private Context mContext;
    private FileInfo mFileInfo;
    private ThreadDao mDao;
    private int mFinished = 0;  //记录下载进度
    public boolean isPause = false;  //是否暂停下载

    public DownloadTask(Context mContext, FileInfo mFileInfo) {
        this.mContext = mContext;
        this.mFileInfo = mFileInfo;
        mDao = new ThreadDaoImpl(mContext);
    }

    public void download(){
        //读取数据库线程信息,是否有已下载但未完成的信息
        List<ThreadInfo> threadInfos = mDao.queryThreads(mFileInfo.getUrl());
        ThreadInfo threadInfo = null;
        if (threadInfos.size() == 0) {  //如果数据库没有线程信息,我们就自己初始化线程信息实体类
            threadInfo = new ThreadInfo(0,mFileInfo.getUrl(),0,mFileInfo.getLength(),0);
        }else {
            threadInfo = threadInfos.get(0);
        }
        //创建子线程,开始下载
        new DownloadThread(threadInfos.get(0)).start();
    }

    /**
     * 下载线程
     */
    class DownloadThread extends Thread {
        private ThreadInfo mThreadInfo;

        public DownloadThread(ThreadInfo mThreadInfo) {
            this.mThreadInfo = mThreadInfo;
        }

        @Override
        public void run() {
            //向数据库插入线程下载信息,插入之前先进行查询,不存在时才进行插入
            if (!mDao.isExistsThread(mThreadInfo.getUrl(), mThreadInfo.getId())) {
                mDao.insertThread(mThreadInfo);
            }
            HttpURLConnection conn = null;
            RandomAccessFile raf = null;
            InputStream is = null;
            //设置下载位置
            try {
                URL url = new URL(mThreadInfo.getUrl());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setRequestMethod("GET");
                //设置下载位置
                int start = mThreadInfo.getStart() + mThreadInfo.getFinished();
                conn.setRequestProperty("Range","bytes="+start+"-"+mThreadInfo.getEnd());
                //设置文件写入位置
                File file  = new File(DownloadService.DOWNLOAD_PATH,mFileInfo.getFileName());
                raf = new RandomAccessFile(file,"rwd");
                raf.seek(start);    //从指定位置写入   例如seek(10),将从第11个字节开始写入,跳过前面10个字节
                //开始下载
                Intent intent = new Intent(DownloadService.ACTION_UPDATE);
                mFinished = mThreadInfo.getFinished();
                if (conn.getResponseCode() == 206) {
                    //读取数据
                    is = conn.getInputStream();
                    byte[] buffer = new byte[1024 * 4];
                    int length = -1;
                    long time = System.currentTimeMillis();
                    while ((length = is.read(buffer)) != -1) {
                        //写入文件
                        raf.write(buffer,0,length);
                        //将下载进度发送给Activity(使用广播)
                        mFinished += length;
                        if (System.currentTimeMillis() - time > 500) {  //每隔500毫秒发送一次广播
                            time = System.currentTimeMillis();
                            intent.putExtra("finished",mFinished * 100 / mFileInfo.getLength());  //以百分比的形式传递数据
                            mContext.sendBroadcast(intent); //发送广播
                        }
                        //当下载暂停时,保存当前下载进度
                        if (isPause) {
                            mDao.updateThread(mThreadInfo.getUrl(),mThreadInfo.getId(),mFinished);
                            return;
                        }
                    }
                    //下载完成时,删除线程信息
                    mDao.deleteThread(mThreadInfo.getUrl(),mThreadInfo.getId());
                }
            }catch (Exception e) {
                e.printStackTrace();
            }finally {
                try {
                    conn.disconnect();
                    raf.close();
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}