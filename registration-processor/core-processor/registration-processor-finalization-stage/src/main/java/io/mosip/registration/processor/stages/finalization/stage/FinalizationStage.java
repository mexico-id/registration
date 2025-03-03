package io.mosip.registration.processor.stages.finalization.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.registration.processor.core.code.*;
import io.mosip.registration.processor.core.constant.*;
import io.mosip.registration.processor.core.constant.EventId;
import io.mosip.registration.processor.core.constant.EventName;
import io.mosip.registration.processor.core.constant.EventType;
import io.mosip.registration.processor.core.curpbiomanager.CurpBioDtorequest;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.packet.storage.utils.PriorityBasedPacketManagerService;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.processor.core.abstractverticle.MessageBusAddress;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.abstractverticle.MosipEventBus;
import io.mosip.registration.processor.core.abstractverticle.MosipRouter;
import io.mosip.registration.processor.core.abstractverticle.MosipVerticleAPIManager;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.exception.util.PlatformSuccessMessages;
import io.mosip.registration.processor.core.logger.LogDescription;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.status.util.StatusUtil;
import io.mosip.registration.processor.core.status.util.TrimExceptionMessage;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.manager.dto.IdResponseDTO;
import io.mosip.registration.processor.packet.manager.exception.IdrepoDraftException;
import io.mosip.registration.processor.packet.manager.exception.IdrepoDraftReprocessableException;
import io.mosip.registration.processor.packet.manager.idreposervice.IdrepoDraftService;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

import java.io.IOException;
import java.util.ArrayList;

@RefreshScope
@Service
@Configuration
@ComponentScan(basePackages = { "${mosip.auth.adapter.impl.basepackage}",
		"io.mosip.registration.processor.core.config",
		"io.mosip.registration.processor.stages.finalization.config",
		"io.mosip.registration.processor.status.config", "io.mosip.registration.processor.rest.client.config",
		"io.mosip.registration.processor.packet.storage.config",
		"io.mosip.registration.processor.stages.config",
		"io.mosip.kernel.packetmanager.config",
		"io.mosip.registration.processor.packet.manager.config",
		"io.mosip.registration.processor.core.kernel.beans"})
public class FinalizationStage extends MosipVerticleAPIManager{
	/** regproc logger */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(FinalizationStage.class);
	/** stage properties prefix */
	private static final String STAGE_PROPERTY_PREFIX = "mosip.regproc.finalization.";
	private static final String USER = "MOSIP_SYSTEM";
	
	/** The mosip event bus. */
	MosipEventBus mosipEventBus = null;
	
	private TrimExceptionMessage trimExceptionMessage = new TrimExceptionMessage();
	
	/** The cluster manager url. */
	@Value("${vertx.cluster.configuration}")
	private String clusterManagerUrl;
	
	/** After this time intervel, message should be considered as expired (In seconds). */
	@Value("${mosip.regproc.biometric.extraction.message.expiry-time-limit}")
	private Long messageExpiryTimeLimit;
	
	/** worker pool size. */
	@Value("${worker.pool.size}")
	private Integer workerPoolSize;
	
	/** The registration status service. */
	@Autowired
	private RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** Mosip router for APIs */
	@Autowired
	private MosipRouter router;

	@Autowired
	private RegistrationProcessorRestClientService registrationProcessorRestClientService;

	/** registration status mapper util */
	@Autowired
	private RegistrationExceptionMapperUtil registrationStatusMapperUtil;
	
	@Autowired
	private IdrepoDraftService idrepoDraftService;
	
	/** The core audit request builder. */
	@Autowired
	private AuditLogRequestBuilder auditLogRequestBuilder;
	@Autowired
	private PriorityBasedPacketManagerService packetManagerService;
	@Autowired
	private ObjectMapper mapper;

	@Override
	protected String getPropertyPrefix() {
		// TODO Auto-generated method stub
		return STAGE_PROPERTY_PREFIX;
	}
	
	/**
	 * Deploy verticle.
	 */
	public void deployVerticle() {
		
		mosipEventBus = this.getEventBus(this, clusterManagerUrl, workerPoolSize);
		this.consumeAndSend(mosipEventBus, MessageBusAddress.FINALIZATION_BUS_IN,
				MessageBusAddress.FINALIZATION_BUS_OUT, messageExpiryTimeLimit);
	}

	@Override
	public void start() {
		router.setRoute(this.postUrl(getVertx(), MessageBusAddress.FINALIZATION_BUS_IN,
				MessageBusAddress.FINALIZATION_BUS_OUT));
		this.createServer(router.getRouter(), getPort());

	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.registration.processor.core.spi.eventbus.EventBusManager#process(
	 * java.lang.Object)
	 */
	@Override
	public MessageDTO process(MessageDTO object) {
		boolean isTransactionSuccessful = Boolean.FALSE;
		object.setMessageBusAddress(MessageBusAddress.FINALIZATION_BUS_IN);
		object.setInternalError(Boolean.FALSE);
		object.setIsValid(Boolean.FALSE);
		LogDescription description = new LogDescription();
		String registrationId = object.getRid();
		InternalRegistrationStatusDto registrationStatusDto =null;
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				registrationId, "FinalizationStage::process()::entry");
		try {
			registrationStatusDto = registrationStatusService.getRegistrationStatus(
					registrationId, object.getReg_type(), object.getIteration(), object.getWorkflowInstanceId());
			registrationStatusDto
					.setLatestTransactionTypeCode(RegistrationTransactionTypeCode.FINALIZATION.toString());
			registrationStatusDto.setRegistrationStageName(getStageName());

			if (!idrepoDraftService.idrepoHasDraft(registrationStatusDto.getRegistrationId())) {
				registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
				registrationStatusDto.setLatestTransactionStatusCode(registrationStatusMapperUtil
						.getStatusCode(RegistrationExceptionTypeCode.DRAFT_REQUEST_UNAVAILABLE));
				description.setTransactionStatusCode(registrationStatusMapperUtil
						.getStatusCode(RegistrationExceptionTypeCode.DRAFT_REQUEST_UNAVAILABLE));
				registrationStatusDto.setStatusComment(trimExceptionMessage
						.trimExceptionMessage(StatusUtil.FINALIZATION_DRAFT_REQUEST_UNAVAILABLE.getMessage()));
				object.setInternalError(Boolean.TRUE);
				isTransactionSuccessful = false;
				description.setMessage(PlatformErrorMessages.RPR_FINALIZATION_STAGE_DRAFT_REQUEST_UNAVAILABLE.getMessage());
				description.setCode(PlatformErrorMessages.RPR_FINALIZATION_STAGE_DRAFT_REQUEST_UNAVAILABLE.getCode());
				description.setSubStatusCode(StatusUtil.FINALIZATION_DRAFT_REQUEST_UNAVAILABLE.getCode());

				regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
						StatusUtil.FINALIZATION_DRAFT_REQUEST_UNAVAILABLE.getMessage());
				object.setIsValid(Boolean.FALSE);
			}
			else {
				IdResponseDTO idResponseDTO = idrepoDraftService.idrepoPublishDraft(registrationStatusDto.getRegistrationId());
				if(idResponseDTO != null && idResponseDTO.getResponse() != null) {
					String handle = packetManagerService.getFieldByMappingJsonKey(registrationId, MappingJsonConstants.CURPID, registrationStatusDto.getRegistrationType(), ProviderStageName.FINALIZATION);
					regProcLogger.info("CurpId fetched by RID:{}", handle);
					curpBioStatusUpdate(handle, registrationStatusDto.getRegistrationType());

					registrationStatusDto.setStatusComment(StatusUtil.FINALIZATION_SUCCESS.getMessage());
					registrationStatusDto.setSubStatusCode(StatusUtil.FINALIZATION_SUCCESS.getCode());
					isTransactionSuccessful = true;
					object.setIsValid(Boolean.TRUE);
					registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
					description.setMessage(PlatformSuccessMessages.RPR_FINALIZATION_SUCCESS.getMessage());
					description.setCode(PlatformSuccessMessages.RPR_FINALIZATION_SUCCESS.getCode());
					description.setTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
				}
			}
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, description.getMessage());
			registrationStatusDto.setUpdatedBy(USER);
		} catch (ApisResourceAccessException ex) {
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.name());
			registrationStatusDto.setStatusComment(trimExceptionMessage
					.trimExceptionMessage(StatusUtil.API_RESOUCE_ACCESS_FAILED.getMessage() + ex.getMessage()));
			registrationStatusDto.setSubStatusCode(StatusUtil.API_RESOUCE_ACCESS_FAILED.getCode());
			registrationStatusDto.setLatestTransactionStatusCode(registrationStatusMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.APIS_RESOURCE_ACCESS_EXCEPTION));
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId,
					RegistrationStatusCode.PROCESSING.toString() + ex.getMessage() + ExceptionUtils.getStackTrace(ex));
			object.setInternalError(Boolean.TRUE);
			description.setMessage(trimExceptionMessage
					.trimExceptionMessage(StatusUtil.API_RESOUCE_ACCESS_FAILED.getMessage() + ex.getMessage()));
			description.setCode(PlatformErrorMessages.RPR_FINALIZATION_STAGE_API_RESOURCE_EXCEPTION.getCode());
		} catch (IdrepoDraftException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId,
					RegistrationStatusCode.FAILED.toString() + e.getMessage() + ExceptionUtils.getStackTrace(e));
			registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.name());
			registrationStatusDto.setStatusComment(
					trimExceptionMessage.trimExceptionMessage(
							StatusUtil.FINALIZATION_IDREPO_DRAFT_EXCEPTION.getMessage() + e.getMessage()));
			registrationStatusDto.setSubStatusCode(StatusUtil.FINALIZATION_IDREPO_DRAFT_EXCEPTION.getCode());
			registrationStatusDto.setLatestTransactionStatusCode(
					registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.IDREPO_DRAFT_EXCEPTION));
			description.setMessage(PlatformErrorMessages.IDREPO_DRAFT_EXCEPTION.getMessage());
			description.setCode(PlatformErrorMessages.IDREPO_DRAFT_EXCEPTION.getCode());
			object.setInternalError(Boolean.TRUE);
			object.setRid(registrationStatusDto.getRegistrationId());
		} catch (IdrepoDraftReprocessableException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId,
					RegistrationStatusCode.PROCESSING.toString() + e.getMessage() + ExceptionUtils.getStackTrace(e));
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.name());
			registrationStatusDto.setStatusComment(trimExceptionMessage
					.trimExceptionMessage(
							StatusUtil.FINALIZATION_IDREPO_DRAFT_REPROCESSABLE_EXCEPTION.getMessage()
									+ e.getMessage()));
			registrationStatusDto
					.setSubStatusCode(StatusUtil.FINALIZATION_IDREPO_DRAFT_REPROCESSABLE_EXCEPTION.getCode());
			registrationStatusDto.setLatestTransactionStatusCode(registrationStatusMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.IDREPO_DRAFT_REPROCESSABLE_EXCEPTION));
			description.setMessage(PlatformErrorMessages.IDREPO_DRAFT_EXCEPTION.getMessage());
			description.setCode(PlatformErrorMessages.IDREPO_DRAFT_EXCEPTION.getCode());
			object.setInternalError(Boolean.TRUE);
			object.setRid(registrationStatusDto.getRegistrationId());
		}catch (Exception ex) {
			registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.name());
			registrationStatusDto.setStatusComment(
					trimExceptionMessage.trimExceptionMessage(StatusUtil.UNKNOWN_EXCEPTION_OCCURED.getMessage()));
			registrationStatusDto.setSubStatusCode(StatusUtil.UNKNOWN_EXCEPTION_OCCURED.getCode());
			registrationStatusDto.setLatestTransactionStatusCode(
					registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.EXCEPTION));
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId,
					RegistrationStatusCode.FAILED.toString() + ex.getMessage() + ExceptionUtils.getStackTrace(ex));
			object.setInternalError(Boolean.TRUE);
			description.setMessage(PlatformErrorMessages.RPR_SYS_UNEXCEPTED_EXCEPTION.getMessage());
			description.setCode(PlatformErrorMessages.RPR_SYS_UNEXCEPTED_EXCEPTION.getCode());
		}
		finally {
			if (description.getStatusComment() != null)
				registrationStatusDto.setStatusComment(description.getStatusComment());
			if (description.getStatusCode() != null)
				registrationStatusDto.setStatusCode(description.getStatusCode());
			if (description.getSubStatusCode() != null)
				registrationStatusDto.setSubStatusCode(description.getSubStatusCode());
			if (description.getTransactionStatusCode() != null)
				registrationStatusDto.setLatestTransactionStatusCode(description.getTransactionStatusCode());

			if (object.getInternalError()) {
				updateErrorFlags(registrationStatusDto, object);
			}
			String moduleId = isTransactionSuccessful
					? PlatformSuccessMessages.RPR_BIOMETRIC_EXTRACTION_SUCCESS.getCode()
					: description.getCode();
			String moduleName = ModuleName.BIOMETRIC_EXTRACTION.toString();
			registrationStatusService.updateRegistrationStatus(registrationStatusDto, moduleId, moduleName);
			String eventId = isTransactionSuccessful ? EventId.RPR_402.toString() : EventId.RPR_405.toString();
			String eventName = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventName.UPDATE.toString()
					: EventName.EXCEPTION.toString();
			String eventType = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventType.BUSINESS.toString()
					: EventType.SYSTEM.toString();

			auditLogRequestBuilder.createAuditRequestBuilder(description.getMessage(), eventId, eventName, eventType,
					moduleId, moduleName, registrationId);

		}
		
		return object;
	}

	private void curpBioStatusUpdate(String handle,String regType ) throws ApisResourceAccessException, IOException {
		CurpBioDtorequest curpBioDtorequest = new CurpBioDtorequest();
		curpBioDtorequest.setCurpId(handle);
		curpBioDtorequest.setCurpType(regType);
		curpBioDtorequest.setIsLatestBio(true);
		curpBioDtorequest.setCurpStatus(RegistrationStatusCode.PROCESSED.name());

		try {
			regProcLogger.info("Update request for CURPMANAGERUPDATE: {}", mapper.writeValueAsString(curpBioDtorequest));
			String response = (String) registrationProcessorRestClientService.postApi(ApiName.CURPMANAGERUPDATE, new ArrayList<>(), "", "", curpBioDtorequest, String.class);
			regProcLogger.info("Received response from CURPMANAGERUPDATE: {}", response);
		} catch (ApisResourceAccessException e) {
			regProcLogger.error("Error while accessing CURPMANAGERUPDATE API", e);
			throw e;
		}
	}


	/**
	 * update Error Flags
	 * @param registrationStatusDto
	 * @param object
	 */
	private void updateErrorFlags(InternalRegistrationStatusDto registrationStatusDto, MessageDTO object) {
		object.setInternalError(true);
		if (registrationStatusDto.getLatestTransactionStatusCode()
				.equalsIgnoreCase(RegistrationTransactionStatusCode.REPROCESS.toString())) {
			object.setIsValid(true);
		} else {
			object.setIsValid(false);
		}
	}
}
