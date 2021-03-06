package com.wix.pay.leumicard.it


import scala.reflect.ClassTag
import scala.util.{Failure, Try}
import org.specs2.matcher.{MatchResult, Matcher}
import org.specs2.mutable.SpecWithJUnit
import org.specs2.specification.Scope
import com.google.api.client.http.HttpRequestFactory
import com.google.api.client.http.javanet.NetHttpTransport
import com.wix.pay.{PaymentErrorException, PaymentRejectedException}
import com.wix.pay.creditcard.{CreditCard, CreditCardOptionalFields, YearMonth}
import com.wix.pay.leumicard._
import com.wix.pay.model._


class LeumiCardGatewayIT extends SpecWithJUnit {
  val leumiCardPort = 10019
  val requestFactory: HttpRequestFactory = new NetHttpTransport().createRequestFactory()

  val driver = new LeumiCardDriver(port = leumiCardPort, password = "some-password")
  val authorizationParser = new JsonLeumiCardAuthorizationParser

  val leumicardGateway = new LeumiCardGateway(
    requestFactory = requestFactory,
    paymentsEndpointUrl = s"http://localhost:$leumiCardPort/",
    password = "some-password")

  val merchantParser = new JsonLeumiCardMerchantParser()

  val merchant = LeumiCardMerchant(masof = "012345678")
  val merchantKey: String = merchantParser.stringify(merchant)
  val payment = Payment(currencyAmount = CurrencyAmount("USD", 33.3), installments = 2)

  val buyerCreditCard = CreditCard(
    "4580458045804580",
    YearMonth(2020, 12),
    Some(CreditCardOptionalFields.withFields(
      csc = Some("123"),
      holderId = Some("0123456"))))

  val deal = Deal(
    id = System.currentTimeMillis().toString,
    title = Some("some deal title"))

  val customer = Customer(name = Some(Name(first = "John", last = "Doe")))

  val successfulTransactionId = "4638202"

  def givenSaleRequestToLeumiCard: driver.SaleContext = {
    driver.aSaleFor(
      masof = merchant.masof,
      payment = payment,
      creditCard = buyerCreditCard,
      customer = customer,
      deal = deal)
  }

  def givenAuthorizeRequestToLeumiCard: driver.AuthorizeContext = {
    driver.anAuthorizeFor(
      masof = merchant.masof,
      payment = payment,
      creditCard = buyerCreditCard,
      customer = customer,
      deal = deal)
  }

  def givenCaptureRequestToLeumiCard: driver.CaptureContext = {
    driver.aCaptureFor(
      masof = merchant.masof,
      currencyAmount = payment.currencyAmount,
      authorizationKey = successfulTransactionId)
  }

  def executeValidSale: Try[String] =
    leumicardGateway.sale(
      merchantKey = merchantKey,
      creditCard = buyerCreditCard,
      payment = payment,
      customer = Some(customer),
      deal = Some(deal))

  def executeValidAuthorize: Try[String] =
    leumicardGateway.authorize(
      merchantKey = merchantKey,
      creditCard = buyerCreditCard,
      payment = payment,
      customer = Some(customer),
      deal = Some(deal))

  def executeValidCapture: Try[String] =
    leumicardGateway.capture(
      merchantKey = merchantKey,
      authorizationKey = authorizationParser.stringify(LeumiCardAuthorization(successfulTransactionId)),
      amount = payment.currencyAmount.amount)

  def assertFailure[T: ClassTag](result: Try[String]): MatchResult[Try[String]] = {
    result must beAFailedTry(check = beAnInstanceOf[T])
  }

  def beAuthorizationKeyWith(transactionId: String): Matcher[String] = {
    beEqualTo(transactionId) ^^ {authorizationParser.parse(_: String).transactionId }
  }


  step {
    driver.start()
  }


  sequential


  "sale request via Leumi Card gateway" should {
    "successfully yield transaction id on valid request" in new Context {
      givenSaleRequestToLeumiCard succeedsWith successfulTransactionId

      val saleResult: Try[String] = executeValidSale

      saleResult must beSuccessfulTry(check = successfulTransactionId)
    }

    "fail for invalid merchant format" in new Context {
      val saleResult: Try[String] = leumicardGateway.sale(
        merchantKey = "bla bla",
        creditCard = buyerCreditCard,
        payment = payment,
        customer = Some(customer),
        deal = Some(deal))

      saleResult must beAnInstanceOf[Failure[PaymentErrorException]]
    }

    "fail when sale is not successful" in new Context {
      givenSaleRequestToLeumiCard.errors()

      val saleResult: Try[String] = executeValidSale

      assertFailure[PaymentErrorException](saleResult)
    }

    "fail if deal is not provided" in new Context {
      val saleResult: Try[String] = leumicardGateway.sale(
        merchantKey = merchantKey,
        creditCard = buyerCreditCard,
        payment = payment,
        customer = Some(customer))

      assertFailure[PaymentErrorException](saleResult)
    }

    "fail if customer is not provided" in new Context {
      val saleResult: Try[String] = leumicardGateway.sale(
        merchantKey = merchantKey,
        creditCard = buyerCreditCard,
        payment = payment,
        deal = Some(deal))

      assertFailure[PaymentErrorException](saleResult)
    }

    "fail with PaymentRejectedException for rejected transactions" in new Context {
      givenSaleRequestToLeumiCard.getsRejected()

      val saleResult: Try[String] = executeValidSale

      assertFailure[PaymentRejectedException](saleResult)
    }

    "fail with PaymentErrorException for erroneous response" in new Context {
      givenSaleRequestToLeumiCard.returnsIllegalMasof()

      val saleResult: Try[String] = executeValidSale

      assertFailure[PaymentErrorException](saleResult)
    }
  }


  "authorize request via Leumi Card gateway" should {
    "successfully yield transaction id on valid request" in new Context {
      givenAuthorizeRequestToLeumiCard succeedsWith successfulTransactionId

      val authorizeResult: Try[String] = executeValidAuthorize

      authorizeResult must beSuccessfulTry(check = beAuthorizationKeyWith(successfulTransactionId))
    }

    "fail for invalid merchant format" in new Context {
      val authorizeResult: Try[String] = leumicardGateway.authorize(
        merchantKey = "bla bla",
        creditCard = buyerCreditCard,
        payment = payment,
        customer = Some(customer),
        deal = Some(deal))

      assertFailure[PaymentErrorException](authorizeResult)
    }

    "fail when authorize is not successful" in new Context {
      givenAuthorizeRequestToLeumiCard.errors()

      val authorizeResult: Try[String] = executeValidSale

      assertFailure[PaymentErrorException](authorizeResult)
    }

    "fail if deal is not provided" in new Context {
      val authorizeResult: Try[String] = leumicardGateway.authorize(
        merchantKey = merchantKey,
        creditCard = buyerCreditCard,
        payment = payment,
        customer = Some(customer))

      assertFailure[PaymentErrorException](authorizeResult)
    }
  }


  "capture request via Leumi Card gateway" should {
    "successfully yield transaction id on valid request" in new Context {
      givenCaptureRequestToLeumiCard succeedsWith successfulTransactionId

      val captureResult: Try[String] = executeValidCapture

      captureResult must beSuccessfulTry(check = successfulTransactionId)
    }

    "fail for invalid merchant format" in new Context {
      val captureResult: Try[String] = leumicardGateway.capture(
        merchantKey = "bla bla",
        amount = payment.currencyAmount.amount,
        authorizationKey = authorizationParser.stringify(LeumiCardAuthorization(successfulTransactionId)))

      assertFailure[PaymentErrorException](captureResult)
    }

    "fail when capture is not successful" in new Context {
      givenCaptureRequestToLeumiCard.errors()

      val captureResult: Try[String] = executeValidCapture

      assertFailure[PaymentErrorException](captureResult)
    }
  }


  trait Context extends Scope {
    driver.reset()
  }
}
