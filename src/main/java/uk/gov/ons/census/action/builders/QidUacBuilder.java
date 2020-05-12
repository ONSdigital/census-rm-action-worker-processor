package uk.gov.ons.census.action.builders;

import static uk.gov.ons.census.action.utility.ActionTypeHelper.isCeIndividualActionType;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import uk.gov.ons.census.action.client.CaseClient;
import uk.gov.ons.census.action.model.UacQidTuple;
import uk.gov.ons.census.action.model.dto.UacQidDTO;
import uk.gov.ons.census.action.model.entity.ActionType;
import uk.gov.ons.census.action.model.entity.Case;
import uk.gov.ons.census.action.model.entity.UacQidLink;
import uk.gov.ons.census.action.model.repository.UacQidLinkRepository;

@Component
public class QidUacBuilder {
  private static final Set<ActionType> uacQidPreGeneratedActionTypes =
      Set.of(
          ActionType.ICHHQE,
          ActionType.ICHHQW,
          ActionType.ICHHQN,
          ActionType.ICL1E,
          ActionType.ICL2W,
          ActionType.ICL4N,
          ActionType.CE1_IC01,
          ActionType.CE1_IC02,
          ActionType.CE_IC03_1,
          ActionType.CE_IC04_1,
          ActionType.SPG_IC11,
          ActionType.SPG_IC12,
          ActionType.SPG_IC13,
          ActionType.SPG_IC14);

  private static final String ADDRESS_LEVEL_ESTAB = "E";

  private static final String COUNTRY_CODE_ENGLAND = "E";
  private static final String COUNTRY_CODE_WALES = "W";
  private static final String COUNTRY_CODE_NORTHERN_IRELAND = "N";

  private static final String CASE_TYPE_HOUSEHOLD = "HH";
  private static final String CASE_TYPE_SPG = "SPG";
  private static final String CASE_TYPE_CE = "CE";

  private static final int NUM_OF_UAC_QID_PAIRS_NEEDED_BY_A_WALES_INITIAL_CONTACT_QUESTIONNAIRE = 2;
  private static final int NUM_OF_UAC_QID_PAIRS_NEEDED_FOR_SINGLE_LANGUAGE = 1;
  private static final String WALES_IN_ENGLISH_QUESTIONNAIRE_TYPE = "02";
  private static final String WALES_IN_WELSH_QUESTIONNAIRE_TYPE = "03";
  private static final String WALES_IN_ENGLISH_QUESTIONNAIRE_TYPE_CE_CASES = "22";
  private static final String WALES_IN_WELSH_QUESTIONNAIRE_TYPE_CE_CASES = "23";
  private static final String UNKNOWN_COUNTRY_ERROR = "Unknown Country";
  private static final String UNEXPECTED_CASE_TYPE_ERROR = "Unexpected Case Type";
  public static final String HOUSEHOLD_INITIAL_CONTACT_QUESTIONNAIRE_TREATMENT_CODE_PREFIX = "HH_Q";
  public static final String CE_INITIAL_CONTACT_QUESTIONNAIRE_TREATMENT_CODE_PREFIX = "CE_Q";
  public static final String SPG_INITIAL_CONTACT_QUESTIONNAIRE_TREATMENT_CODE_PREFIX = "SPG_Q";
  public static final String WALES_TREATMENT_CODE_SUFFIX = "W";

  private final UacQidLinkRepository uacQidLinkRepository;
  private final CaseClient caseClient;

  public QidUacBuilder(UacQidLinkRepository uacQidLinkRepository, CaseClient caseClient) {
    this.uacQidLinkRepository = uacQidLinkRepository;
    this.caseClient = caseClient;
  }

  public UacQidTuple getUacQidLinks(Case linkedCase, ActionType actionType) {

    if (isUacQidPreGeneratedActionType(actionType)) {
      return fetchExistingUacQidPairsForAction(linkedCase, actionType);
    } else if (isCeIndividualActionType(actionType)) {
      // We override the address level for these action types because we want to create individual
      // uac qid pairs
      return createNewUacQidPairsForAction(linkedCase, actionType, "U");
    } else {
      return createNewUacQidPairsForAction(linkedCase, actionType);
    }
  }

  private UacQidTuple fetchExistingUacQidPairsForAction(Case linkedCase, ActionType actionType) {
    String caseId = linkedCase.getCaseId().toString();

    List<UacQidLink> uacQidLinks = uacQidLinkRepository.findByCaseId(caseId);

    if (uacQidLinks == null || uacQidLinks.isEmpty()) {
      throw new RuntimeException(
          String.format("We can't process this case id '%s' with no UACs", caseId));

    } else if ((!actionType.equals(ActionType.ICHHQW) && !actionType.equals(ActionType.SPG_IC14))
        && isStateCorrectForSingleUacQidPair(linkedCase, uacQidLinks)) {
      return getUacQidTupleWithSinglePair(uacQidLinks);

    } else if ((actionType.equals(ActionType.ICHHQW) || actionType.equals(ActionType.SPG_IC14))
        && isStateCorrectForSecondWelshUacQidPair(linkedCase, uacQidLinks)) {
      return getUacQidTupleWithSecondWelshPair(uacQidLinks, actionType);

    } else {
      throw new RuntimeException(
          String.format("Wrong number of UACs for treatment code '%s'", actionType));
    }
  }

  private boolean isStateCorrectForSingleUacQidPair(Case linkedCase, List<UacQidLink> uacQidLinks) {
    return !isQuestionnaireWelsh(linkedCase.getTreatmentCode())
        && uacQidLinks.size() == NUM_OF_UAC_QID_PAIRS_NEEDED_FOR_SINGLE_LANGUAGE;
  }

  private boolean isStateCorrectForSecondWelshUacQidPair(
      Case linkedCase, List<UacQidLink> uacQidLinks) {
    return isQuestionnaireWelsh(linkedCase.getTreatmentCode())
        && uacQidLinks.size()
            == NUM_OF_UAC_QID_PAIRS_NEEDED_BY_A_WALES_INITIAL_CONTACT_QUESTIONNAIRE;
  }

  private UacQidTuple getUacQidTupleWithSinglePair(List<UacQidLink> uacQidLinks) {
    UacQidTuple uacQidTuple = new UacQidTuple();
    uacQidTuple.setUacQidLink(uacQidLinks.get(0));
    return uacQidTuple;
  }

  private UacQidTuple getUacQidTupleWithSecondWelshPair(
      List<UacQidLink> uacQidLinks, ActionType actionType) {
    UacQidTuple uacQidTuple = new UacQidTuple();
    String primaryQuestionnaireType = WALES_IN_ENGLISH_QUESTIONNAIRE_TYPE;
    String secondaryQuestionnaireType = WALES_IN_WELSH_QUESTIONNAIRE_TYPE;

    if (actionType.equals(ActionType.CE_IC10)) {
      primaryQuestionnaireType = WALES_IN_ENGLISH_QUESTIONNAIRE_TYPE_CE_CASES;
      secondaryQuestionnaireType = WALES_IN_WELSH_QUESTIONNAIRE_TYPE_CE_CASES;
    }

    uacQidTuple.setUacQidLink(
        getSpecificUacQidLinkByQuestionnaireType(
            uacQidLinks, primaryQuestionnaireType, secondaryQuestionnaireType));
    uacQidTuple.setUacQidLinkWales(
        Optional.of(
            getSpecificUacQidLinkByQuestionnaireType(
                uacQidLinks, secondaryQuestionnaireType, primaryQuestionnaireType)));
    return uacQidTuple;
  }

  private UacQidTuple createNewUacQidPairsForAction(
      Case linkedCase, ActionType actionType, String addressLevel) {
    UacQidTuple uacQidTuple = new UacQidTuple();
    uacQidTuple.setUacQidLink(
        createNewUacQidPair(
            linkedCase,
            calculateQuestionnaireType(
                linkedCase.getCaseType(), linkedCase.getRegion(), addressLevel)));
    if (actionType.equals(ActionType.P_QU_H2)) {
      uacQidTuple.setUacQidLinkWales(
          Optional.of(createNewUacQidPair(linkedCase, WALES_IN_WELSH_QUESTIONNAIRE_TYPE)));
    } else if (actionType.equals(ActionType.CE_IC10)) {
      uacQidTuple.setUacQidLinkWales(
          Optional.of(createNewUacQidPair(linkedCase, WALES_IN_WELSH_QUESTIONNAIRE_TYPE_CE_CASES)));
    }
    return uacQidTuple;
  }

  private UacQidTuple createNewUacQidPairsForAction(Case linkedCase, ActionType actionType) {
    return createNewUacQidPairsForAction(linkedCase, actionType, linkedCase.getAddressLevel());
  }

  private UacQidLink createNewUacQidPair(Case linkedCase, String questionnaireType) {
    UacQidDTO newUacQidPair = caseClient.getUacQid(linkedCase.getCaseId(), questionnaireType);
    UacQidLink newUacQidLink = new UacQidLink();
    newUacQidLink.setCaseId(linkedCase.getCaseId().toString());
    newUacQidLink.setQid(newUacQidPair.getQid());
    newUacQidLink.setUac(newUacQidPair.getUac());
    // Don't persist the new UAC QID link here, that is handled by our eventual consistency model in
    // the API request
    return newUacQidLink;
  }

  private boolean isQuestionnaireWelsh(String treatmentCode) {
    return ((treatmentCode.startsWith(HOUSEHOLD_INITIAL_CONTACT_QUESTIONNAIRE_TREATMENT_CODE_PREFIX)
            || treatmentCode.startsWith(CE_INITIAL_CONTACT_QUESTIONNAIRE_TREATMENT_CODE_PREFIX)
            || treatmentCode.startsWith(SPG_INITIAL_CONTACT_QUESTIONNAIRE_TREATMENT_CODE_PREFIX))
        && treatmentCode.endsWith(WALES_TREATMENT_CODE_SUFFIX));
  }

  private UacQidLink getSpecificUacQidLinkByQuestionnaireType(
      List<UacQidLink> uacQidLinks,
      String wantedQuestionnaireType,
      String otherAllowableQuestionnaireType) {
    for (UacQidLink uacQidLink : uacQidLinks) {
      if (uacQidLink.getQid().startsWith(wantedQuestionnaireType)) {
        return uacQidLink;
      } else if (!uacQidLink.getQid().startsWith(otherAllowableQuestionnaireType)) {
        throw new RuntimeException(
            String.format("Non allowable type  '%s' on case", uacQidLink.getQid()));
      }
    }

    throw new RuntimeException(
        String.format("Can't find UAC QID '%s' for case", otherAllowableQuestionnaireType));
  }

  private boolean isUacQidPreGeneratedActionType(ActionType actionType) {
    return uacQidPreGeneratedActionTypes.contains(actionType);
  }

  public static String calculateQuestionnaireType(
      String caseType, String region, String addressLevel) {
    String country = region.substring(0, 1);
    if (!country.equals(COUNTRY_CODE_ENGLAND)
        && !country.equals(COUNTRY_CODE_WALES)
        && !country.equals(COUNTRY_CODE_NORTHERN_IRELAND)) {
      throw new IllegalArgumentException(String.format("Unknown Country: %s", caseType));
    }

    if (isCeCaseType(caseType) && addressLevel.equals("U")) {
      switch (country) {
        case COUNTRY_CODE_ENGLAND:
          return "21";
        case COUNTRY_CODE_WALES:
          return "22";
        case COUNTRY_CODE_NORTHERN_IRELAND:
          return "24";
      }
    } else if (isHouseholdCaseType(caseType) || isSpgCaseType(caseType)) {
      switch (country) {
        case COUNTRY_CODE_ENGLAND:
          return "01";
        case COUNTRY_CODE_WALES:
          return "02";
        case COUNTRY_CODE_NORTHERN_IRELAND:
          return "04";
      }
    } else if (isCE1RequestForEstabCeCase(caseType, addressLevel)) {
      switch (country) {
        case COUNTRY_CODE_ENGLAND:
          return "31";
        case COUNTRY_CODE_WALES:
          return "32";
        case COUNTRY_CODE_NORTHERN_IRELAND:
          return "34";
      }
    } else {
      throw new IllegalArgumentException(String.format("Unexpected Case Type: %s", caseType));
    }

    throw new RuntimeException(String.format("Unprocessable Case Type '%s'", caseType));
  }

  private static boolean isCE1RequestForEstabCeCase(String caseType, String addressLevel) {
    return isCeCaseType(caseType) && addressLevel.equals(ADDRESS_LEVEL_ESTAB);
  }

  private static boolean isSpgCaseType(String caseType) {
    return caseType.equals(CASE_TYPE_SPG);
  }

  private static boolean isHouseholdCaseType(String caseType) {
    return caseType.equals(CASE_TYPE_HOUSEHOLD);
  }

  private static boolean isCeCaseType(String caseType) {
    return caseType.equals(CASE_TYPE_CE);
  }
}
