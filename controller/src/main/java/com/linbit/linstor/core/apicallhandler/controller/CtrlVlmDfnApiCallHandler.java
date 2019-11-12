package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.ImplementationError;
import com.linbit.ValueOutOfRangeException;
import com.linbit.crypto.LengthPadding;
import com.linbit.crypto.SymmetricKeyCipher;
import com.linbit.drbd.md.GidGenerator;
import com.linbit.linstor.LinStorException;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiCallRcImpl.ApiCallRcEntry;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.prop.LinStorObject;
import com.linbit.linstor.core.CtrlSecurityObjects;
import com.linbit.linstor.core.SecretGenerator;
import com.linbit.linstor.core.apicallhandler.ScopeRunner;
import com.linbit.linstor.core.apicallhandler.controller.internal.CtrlSatelliteUpdateCaller;
import com.linbit.linstor.core.apicallhandler.controller.internal.CtrlSatelliteUpdater;
import com.linbit.linstor.core.apicallhandler.response.ApiAccessDeniedException;
import com.linbit.linstor.core.apicallhandler.response.ApiOperation;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.core.apicallhandler.response.CtrlResponseUtils;
import com.linbit.linstor.core.apicallhandler.response.ResponseContext;
import com.linbit.linstor.core.apicallhandler.response.ResponseConverter;
import com.linbit.linstor.core.apis.VolumeDefinitionApi;
import com.linbit.linstor.core.apis.VolumeDefinitionWtihCreationPayload;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.VolumeDefinition;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.locks.LockGuardFactory;
import com.linbit.utils.Base64;

import static com.linbit.linstor.core.apicallhandler.controller.CtrlRscDfnApiCallHandler.getRscDfnDescriptionInline;
import static com.linbit.locks.LockGuardFactory.LockObj.RSC_DFN_MAP;
import static com.linbit.locks.LockGuardFactory.LockType.WRITE;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import reactor.core.publisher.Flux;

@Singleton
class CtrlVlmDfnApiCallHandler
{
    private static final int SECRET_KEY_BYTES = 20;

    private final ErrorReporter errorReporter;
    private final AccessContext apiCtx;
    private final CtrlTransactionHelper ctrlTransactionHelper;
    private final CtrlPropsHelper ctrlPropsHelper;
    private final CtrlVlmDfnCrtApiHelper ctrlVlmDfnCrtApiHelper;
    private final CtrlVlmCrtApiHelper ctrlVlmCrtApiHelper;
    private final CtrlApiDataLoader ctrlApiDataLoader;
    private final CtrlSecurityObjects secObjs;
    private final CtrlSatelliteUpdater ctrlSatelliteUpdater;
    private final ResponseConverter responseConverter;
    private final Provider<Peer> peer;
    private final Provider<AccessContext> peerAccCtx;
    private final LengthPadding cryptoLenPad;
    private final ScopeRunner scopeRunner;
    private final LockGuardFactory lockGuardFactory;
    private final CtrlSatelliteUpdateCaller ctrlSatelliteUpdateCaller;


    @Inject
    CtrlVlmDfnApiCallHandler(
        ErrorReporter errorReporterRef,
        @ApiContext AccessContext apiCtxRef,
        CtrlTransactionHelper ctrlTransactionHelperRef,
        CtrlPropsHelper ctrlPropsHelperRef,
        CtrlVlmDfnCrtApiHelper ctrlVlmDfnCrtApiHelperRef,
        CtrlVlmCrtApiHelper ctrlVlmCrtApiHelperRef,
        CtrlApiDataLoader ctrlApiDataLoaderRef,
        CtrlSecurityObjects secObjsRef,
        CtrlSatelliteUpdater ctrlSatelliteUpdaterRef,
        ResponseConverter responseConverterRef,
        Provider<Peer> peerRef,
        @PeerContext Provider<AccessContext> peerAccCtxRef,
        LengthPadding cryptoLenPadRef,
        ScopeRunner scopeRunnerRef,
        LockGuardFactory lockGuardFactoryRef,
        CtrlSatelliteUpdateCaller ctrlSatelliteUpdateCallerRef
    )
    {
        errorReporter = errorReporterRef;
        apiCtx = apiCtxRef;
        ctrlTransactionHelper = ctrlTransactionHelperRef;
        ctrlPropsHelper = ctrlPropsHelperRef;
        ctrlVlmDfnCrtApiHelper = ctrlVlmDfnCrtApiHelperRef;
        ctrlVlmCrtApiHelper = ctrlVlmCrtApiHelperRef;
        ctrlApiDataLoader = ctrlApiDataLoaderRef;
        secObjs = secObjsRef;
        ctrlSatelliteUpdater = ctrlSatelliteUpdaterRef;
        responseConverter = responseConverterRef;
        peer = peerRef;
        peerAccCtx = peerAccCtxRef;
        cryptoLenPad = cryptoLenPadRef;
        scopeRunner = scopeRunnerRef;
        lockGuardFactory = lockGuardFactoryRef;
        ctrlSatelliteUpdateCaller = ctrlSatelliteUpdateCallerRef;
    }

    Flux<ApiCallRc> createVolumeDefinitions(
        String rscNameStr,
        List<VolumeDefinitionWtihCreationPayload> vlmDfnWithPayloadApiList
    )
    {
        Map<String, String> objRefs = new TreeMap<>();
        objRefs.put(ApiConsts.KEY_RSC_DFN, rscNameStr);

        ResponseContext context = new ResponseContext(
            ApiOperation.makeCreateOperation(),
            "Volume definitions for " + getRscDfnDescriptionInline(rscNameStr),
            "volume definitions for " + getRscDfnDescriptionInline(rscNameStr),
            ApiConsts.MASK_VLM_DFN,
            objRefs
        );
        return scopeRunner
            .fluxInTransactionalScope(
                "Create volume definitions",
                lockGuardFactory.buildDeferred(WRITE, RSC_DFN_MAP),
                () -> createVlmDfnsInTransaction(
                    context,
                    rscNameStr,
                    vlmDfnWithPayloadApiList
                )
            ).transform(responses -> responseConverter.reportingExceptions(context, responses));
    }

    private Flux<ApiCallRc> createVlmDfnsInTransaction(
        ResponseContext context,
        String rscNameStr,
        List<VolumeDefinitionWtihCreationPayload> vlmDfnWithPayloadApiList
    )
    {
        Flux<ApiCallRc> flux = Flux.empty();
        ApiCallRcImpl apiCallRcs = new ApiCallRcImpl();

        try
        {
            if (vlmDfnWithPayloadApiList.isEmpty())
            {
                throw new ApiRcException(ApiCallRcImpl
                    .entryBuilder(
                        ApiConsts.MASK_WARN,
                        "Volume definition list to create is empty."
                    )
                    .setDetails("Volume definition list that should be added to the resource is empty.")
                    .build()
                );
            }

            ResourceDefinition rscDfn = ctrlApiDataLoader.loadRscDfn(rscNameStr, true);

            Iterator<Resource> iterateResource = getRscIterator(rscDfn);
            List<Resource> rscList = new ArrayList<>();
            while (iterateResource.hasNext())
            {
                rscList.add(iterateResource.next());
            }

            List<VolumeDefinition> vlmDfnsCreated = createVlmDfns(apiCallRcs, rscDfn, vlmDfnWithPayloadApiList);

            Set<NodeName> nodeNames = new TreeSet<>();
            for (VolumeDefinition vlmDfn : vlmDfnsCreated)
            {
                for (Resource rsc : rscList)
                {
                    ctrlVlmCrtApiHelper.createVolumeResolvingStorPool(
                        rsc,
                        vlmDfn,
                        null
                    );
                    nodeNames.add(rsc.getNode().getName());
                }
            }

            ctrlTransactionHelper.commit();

            for (VolumeDefinition vlmDfn : vlmDfnsCreated)
            {
                responseConverter.addWithOp(apiCallRcs, context, createVlmDfnCrtSuccessEntry(vlmDfn, rscNameStr));
            }

            flux = ctrlSatelliteUpdateCaller.updateSatellites(rscDfn, Flux.empty())
                .transform(
                    updateResponses -> CtrlResponseUtils.combineResponses(
                        updateResponses,
                        rscDfn.getName(),
                        nodeNames,
                        "Created volume for resource {1} on {0}",
                        null
                    )
                );
        }
        catch (Exception | ImplementationError exc)
        {
            apiCallRcs = responseConverter.reportException(peer.get(), context, exc);
        }

        return Flux.just((ApiCallRc) apiCallRcs).concatWith(flux);
    }

    List<VolumeDefinition> createVlmDfns(
        ApiCallRcImpl responses,
        ResourceDefinition rscDfn,
        List<VolumeDefinitionWtihCreationPayload> vlmDfnWithPayloadApiListRef
    )
    {
        List<VolumeDefinition> vlmDfns = new ArrayList<>();
        for (VolumeDefinitionWtihCreationPayload vlmDfnApi : vlmDfnWithPayloadApiListRef)
        {
            vlmDfns.add(createVlmDfn(responses, rscDfn, vlmDfnApi));
        }
        return vlmDfns;
    }

    /**
     * Throws contextualized exceptions.
     */
    VolumeDefinition createVlmDfn(
        ApiCallRcImpl responses,
        ResourceDefinition rscDfn,
        VolumeDefinitionWtihCreationPayload vlmDfnApiRef
    )
    {
        VolumeNumber volNr = getOrGenerateVlmNr(
            vlmDfnApiRef.getVlmDfn(),
            rscDfn,
            apiCtx
        );

        ResponseContext context = makeVlmDfnContext(
            ApiOperation.makeCreateOperation(),
            rscDfn.getName().displayValue,
            volNr.value
        );

        VolumeDefinition vlmDfn;
        try
        {
            long size = vlmDfnApiRef.getVlmDfn().getSize();

            VolumeDefinition.Flags[] vlmDfnInitFlags =
                VolumeDefinition.Flags.restoreFlags(vlmDfnApiRef.getVlmDfn().getFlags());

            vlmDfn = ctrlVlmDfnCrtApiHelper.createVlmDfnData(
                peerAccCtx.get(),
                rscDfn,
                volNr,
                vlmDfnApiRef.getDrbdMinorNr(),
                size,
                vlmDfnInitFlags
            );
            Props vlmDfnProps = getVlmDfnProps(vlmDfn);
            Map<String, String> propsMap = vlmDfnProps.map();

            ctrlPropsHelper.fillProperties(
                responses, LinStorObject.VOLUME_DEFINITION, vlmDfnApiRef.getVlmDfn().getProps(),
                vlmDfnProps, ApiConsts.FAIL_ACC_DENIED_VLM_DFN);

            if (vlmDfnProps.getProp(ApiConsts.KEY_DRBD_CURRENT_GI, ApiConsts.NAMESPC_DRBD_OPTIONS) == null)
            {
                // Set an initial DRBD current generation identifier (if not already set, i.e. when migrating)
                // for use when creating volumes in a setup that includes thin provisioning storage pools
                vlmDfnProps.setProp(
                    ApiConsts.KEY_DRBD_CURRENT_GI,
                    GidGenerator.generateRandomGid()
                );
            }

            if (Arrays.asList(vlmDfnInitFlags).contains(VolumeDefinition.Flags.ENCRYPTED))
            {
                byte[] masterKey = secObjs.getCryptKey();
                if (masterKey == null || masterKey.length == 0)
                {
                    throw new ApiRcException(ApiCallRcImpl
                        .entryBuilder(ApiConsts.FAIL_NOT_FOUND_CRYPT_KEY,
                            "Unable to create an encrypted volume definition without having a master key")
                        .setCause("The masterkey was not initialized yet")
                        .setCorrection("Create or enter the master passphrase")
                        .build()
                    );
                }

                String vlmDfnKeyPlain = SecretGenerator.generateSecretString(SECRET_KEY_BYTES);
                SymmetricKeyCipher cipher;
                cipher = SymmetricKeyCipher.getInstanceWithKey(masterKey);

                byte[] encodedData = cryptoLenPad.conceal(vlmDfnKeyPlain.getBytes());
                byte[] encryptedVlmDfnKey = cipher.encrypt(encodedData);

                propsMap.put(
                    ApiConsts.KEY_STOR_POOL_CRYPT_PASSWD,
                    Base64.encode(encryptedVlmDfnKey)
                );
            }
        }
        catch (Exception | ImplementationError exc)
        {
            throw new ApiRcException(responseConverter.exceptionToResponse(peer.get(), context, exc), exc, true);
        }

        return vlmDfn;
    }

    private Iterator<Resource> getRscIterator(ResourceDefinition rscDfn)
    {
        Iterator<Resource> iterator;
        try
        {
            iterator = rscDfn.iterateResource(apiCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ImplementationError(accDeniedExc);
        }
        return iterator;
    }

    private VolumeNumber getOrGenerateVlmNr(
        VolumeDefinitionApi vlmDfnApi,
        ResourceDefinition rscDfn,
        AccessContext accCtx
    )
    {
        VolumeNumber vlmNr;
        try
        {
            vlmNr = CtrlRscDfnApiCallHandler.getVlmNr(vlmDfnApi, rscDfn, accCtx);
        }
        catch (ValueOutOfRangeException valOORangeExc)
        {
            throw new ApiRcException(ApiCallRcImpl.simpleEntry(ApiConsts.FAIL_INVLD_VLM_NR, String.format(
                "The specified volume number '%d' is invalid. Volume numbers have to be in range of %d - %d.",
                vlmDfnApi.getVolumeNr(),
                VolumeNumber.VOLUME_NR_MIN,
                VolumeNumber.VOLUME_NR_MAX
            )), valOORangeExc);
        }
        catch (LinStorException linStorExc)
        {
            throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                ApiConsts.FAIL_POOL_EXHAUSTED_VLM_NR,
                "An exception occurred during generation of a volume number."
            ), linStorExc);
        }
        return vlmNr;
    }

    private Props getVlmDfnProps(VolumeDefinition vlmDfn)
    {
        Props props;
        try
        {
            props = vlmDfn.getProps(peerAccCtx.get());
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "access the properties of " + getVlmDfnDescriptionInline(vlmDfn),
                ApiConsts.FAIL_ACC_DENIED_VLM_DFN
            );
        }
        return props;
    }

    private ApiCallRcEntry createVlmDfnCrtSuccessEntry(VolumeDefinition vlmDfn, String rscNameStr)
    {
        ApiCallRcEntry vlmDfnCrtSuccessEntry = new ApiCallRcEntry();
        vlmDfnCrtSuccessEntry.setReturnCode(ApiConsts.CREATED);
        String successMessage = String.format(
            "New volume definition with number '%d' of resource definition '%s' created.",
            vlmDfn.getVolumeNumber().value,
            rscNameStr
        );
        vlmDfnCrtSuccessEntry.setMessage(successMessage);
        vlmDfnCrtSuccessEntry.putObjRef(ApiConsts.KEY_RSC_DFN, rscNameStr);
        vlmDfnCrtSuccessEntry.putObjRef(ApiConsts.KEY_VLM_NR, Integer.toString(vlmDfn.getVolumeNumber().value));

        errorReporter.logInfo(successMessage);
        return vlmDfnCrtSuccessEntry;
    }

    public static String getVlmDfnDescription(String rscName, Integer vlmNr)
    {
        return "Resource definition: " + rscName + ", Volume number: " + vlmNr;
    }

    public static String getVlmDfnDescriptionInline(VolumeDefinition vlmDfn)
    {
        return getVlmDfnDescriptionInline(vlmDfn.getResourceDefinition(), vlmDfn.getVolumeNumber());
    }

    public static String getVlmDfnDescriptionInline(ResourceDefinition rscDfn, VolumeNumber volNr)
    {
        return getVlmDfnDescriptionInline(rscDfn.getName().displayValue, volNr.value);
    }

    public static String getVlmDfnDescriptionInline(String rscName, Integer vlmNr)
    {
        return "volume definition with number '" + vlmNr + "' of resource definition '" + rscName + "'";
    }

    static ResponseContext makeVlmDfnContext(
        ApiOperation operation,
        String rscNameStr,
        int volumeNr
    )
    {
        Map<String, String> objRefs = new TreeMap<>();
        objRefs.put(ApiConsts.KEY_RSC_DFN, rscNameStr);
        objRefs.put(ApiConsts.KEY_VLM_NR, Integer.toString(volumeNr));

        return new ResponseContext(
            operation,
            getVlmDfnDescription(rscNameStr, volumeNr),
            getVlmDfnDescriptionInline(rscNameStr, volumeNr),
            ApiConsts.MASK_VLM_DFN,
            objRefs
        );
    }
}
