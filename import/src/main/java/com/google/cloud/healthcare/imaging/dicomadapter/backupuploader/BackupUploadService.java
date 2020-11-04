package com.google.cloud.healthcare.imaging.dicomadapter.backupuploader;

import com.google.cloud.healthcare.IDicomWebClient;
import com.google.cloud.healthcare.imaging.dicomadapter.monitoring.Event;
import com.google.cloud.healthcare.imaging.dicomadapter.monitoring.MonitoringService;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupUploadService implements IBackupUploadService {

  private final DelayCalculator delayCalculator;
  private final IBackupUploader backupUploader;
  private final int attemptsAmount;
  private final BackupFlags backupFlags;

  private Logger log = LoggerFactory.getLogger(this.getClass());

  /**
   * Create BackupUploadService instance.
   * @param backupUploader DAO with simple write/read/remove operations.
   * @param delayCalculator util class for reSend tasks schedule delay calculation.
   */
  public BackupUploadService(IBackupUploader backupUploader, BackupFlags backupFlags, DelayCalculator delayCalculator) {
    this.backupUploader = backupUploader;
    this.delayCalculator = delayCalculator;
    this.attemptsAmount = backupFlags.getAttemptsAmount();
    this.backupFlags = backupFlags;
  }

  @Override
  public BackupState createBackup(InputStream inputStream, String uniqueFileName) throws IBackupUploader.BackupException {
    log.debug("sopInstanceUID={}, start write to backup... ", uniqueFileName);
    backupUploader.doWriteBackup(inputStream, uniqueFileName);
    log.debug("sopInstanceUID={}, backup saved.", uniqueFileName);
    return new BackupState(uniqueFileName, attemptsAmount);
  }

  @Override
  public InputStream getBackupStream(String uniqueFileName) throws IBackupUploader.BackupException {
    log.debug("sopInstanceUID={}, start read from backup... ", uniqueFileName);
    InputStream inputStream = backupUploader.doReadBackup(uniqueFileName);
    log.debug("sopInstanceUID={}, backup readed successfully.", uniqueFileName);
    return inputStream;
  }

  @Override
  public void startUploading(IDicomWebClient webClient, BackupState backupState) throws IBackupUploader.BackupException {
    scheduleUploadWithDelay(webClient, backupState);
  }

  @Override
  public void removeBackup(String fileName) {
    try {
      backupUploader.doRemoveBackup(fileName);
      log.debug("sopInstanceUID={}, removeBackup successful.", fileName);
    } catch (IOException ex) {
      MonitoringService.addEvent(Event.CSTORE_BACKUP_ERROR);
      log.error("sopInstanceUID={}, removeBackup failed.", fileName, ex);
    }
  }

  public boolean filterHttpCode(Integer actualHttpStatus) {
    return actualHttpStatus >= 500 || backupFlags.getHttpErrorCodesToRetry().contains(actualHttpStatus);
  }

  private void scheduleUploadWithDelay(IDicomWebClient webClient, BackupState backupState) throws IBackupUploader.BackupException {
    String uniqueFileName = backupState.getUniqueFileName();
    if (backupState.decrement()) {
      int attemptNumber = attemptsAmount - backupState.getAttemptsCountdown();

      log.info("Trying to resend data, sopInstanceUID={}, attempt № {}. ", uniqueFileName, attemptNumber);
      CompletableFuture completableFuture =
          CompletableFuture.runAsync(
              () -> {
                try {
                  InputStream inputStream = readBackupExceptionally(uniqueFileName);
                  webClient.stowRs(inputStream);
                  removeBackup(uniqueFileName);
                  log.debug("sopInstanceUID={}, resend attempt № {}, - successful.", uniqueFileName, attemptNumber);
                } catch (IDicomWebClient.DicomWebException dwe) {
                  log.error("sopInstanceUID={}, resend attempt № {} - failed.", uniqueFileName, attemptNumber, dwe);
                  int dicomStatus = dwe.getStatus();

                  if (filterHttpCode(dwe.getHttpStatus())) {
                    if (backupState.getAttemptsCountdown() > 0) {
                      scheduleUploadWithDelayExceptionally(webClient, backupState);
                      MonitoringService.addEvent(Event.CSTORE_BACKUP_ERROR);
                    } else {
                      throwOnNoResendAttemptsLeft(dwe, uniqueFileName);
                    }
                  } else {
                    throwOnHttpFilterFail(dwe, dwe.getHttpStatus());
                  }
                }
              },
              CompletableFuture.delayedExecutor(
                  delayCalculator.getExponentialDelayMillis(backupState.getAttemptsCountdown(), backupFlags),
                  TimeUnit.MILLISECONDS));

      try {
        completableFuture.get();
      } catch (InterruptedException ie) {
        log.error("scheduleUploadWithDelay Runnable task canceled.", ie);
        Thread.currentThread().interrupt();
        throw new IBackupUploader.BackupException(ie);
      } catch (ExecutionException eex) {
        throw new IBackupUploader.BackupException(eex.getCause());
      }
    } else {
      throw getNoResendAttemptLeftException(null, uniqueFileName);
    }
  }

  private void scheduleUploadWithDelayExceptionally(IDicomWebClient webClient, BackupState backupState) {
    try {
      scheduleUploadWithDelay(webClient, backupState);
    } catch (IBackupUploader.BackupException ex) {
      throw new CompletionException(ex);
    }
  }

  private void throwOnNoResendAttemptsLeft(IDicomWebClient.DicomWebException dwe, String uniqueFileName) throws CompletionException {
    throw new CompletionException(getNoResendAttemptLeftException(dwe, uniqueFileName));
  }

  private void throwOnHttpFilterFail(IDicomWebClient.DicomWebException dwe, int httpCode) throws CompletionException {
    String errorMessage = "Not retried due to HTTP code=" + httpCode;
    log.debug(errorMessage);
    throw new CompletionException(new IBackupUploader.BackupException(dwe.getStatus(), dwe, errorMessage));
  }

  private IBackupUploader.BackupException getNoResendAttemptLeftException(IDicomWebClient.DicomWebException dwe, String uniqueFileName) {
    String errorMessage = "sopInstanceUID=" + uniqueFileName + ". No resend attempt left.";
    log.debug(errorMessage);
    if (dwe != null) {
      return new IBackupUploader.BackupException(dwe.getStatus(), dwe, errorMessage);
    } else {
      return new IBackupUploader.BackupException(errorMessage);
    }
  }

  private InputStream readBackupExceptionally(String fileName) throws CompletionException {
    try {
      return backupUploader.doReadBackup(fileName);
    } catch (IBackupUploader.BackupException ex) {
      log.error("sopInstanceUID={}, read backup failed.", fileName, ex.getCause());
      throw new CompletionException(ex);
    }
  }
}
