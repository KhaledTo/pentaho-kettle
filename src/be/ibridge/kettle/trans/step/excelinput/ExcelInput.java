/**********************************************************************
 **                                                                   **
 **               This code belongs to the KETTLE project.            **
 **                                                                   **
 ** Kettle, from version 2.2 on, is released into the public domain   **
 ** under the Lesser GNU Public License (LGPL).                       **
 **                                                                   **
 ** For more details, please read the document LICENSE.txt, included  **
 ** in this project                                                   **
 **                                                                   **
 ** http://www.kettle.be                                              **
 ** info@kettle.be                                                    **
 **                                                                   **
 **********************************************************************/

package be.ibridge.kettle.trans.step.excelinput;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

import jxl.BooleanCell;
import jxl.Cell;
import jxl.CellType;
import jxl.DateCell;
import jxl.LabelCell;
import jxl.NumberCell;
import jxl.Sheet;
import jxl.Workbook;
import be.ibridge.kettle.core.Row;
import be.ibridge.kettle.core.exception.KettleException;
import be.ibridge.kettle.core.value.Value;
import be.ibridge.kettle.trans.Trans;
import be.ibridge.kettle.trans.TransMeta;
import be.ibridge.kettle.trans.step.BaseStep;
import be.ibridge.kettle.trans.step.StepDataInterface;
import be.ibridge.kettle.trans.step.StepInterface;
import be.ibridge.kettle.trans.step.StepMeta;
import be.ibridge.kettle.trans.step.StepMetaInterface;
import be.ibridge.kettle.trans.step.errorhandling.CompositeFileErrorHandler;
import be.ibridge.kettle.trans.step.errorhandling.FileErrorHandlerContentLineNumber;
import be.ibridge.kettle.trans.step.errorhandling.FileErrorHandlerMissingFiles;
import be.ibridge.kettle.trans.step.fileinput.FileInputList;
import be.ibridge.kettle.trans.step.playlist.FilePlayListAll;
import be.ibridge.kettle.trans.step.playlist.FilePlayListReplay;

/**
 * This class reads data from one or more Microsoft Excel files.
 * 
 * @author Matt
 * @since 19-NOV-2003
 * 
 */
public class ExcelInput extends BaseStep implements StepInterface
{
	private ExcelInputMeta meta;

	private ExcelInputData data;

	public ExcelInput(StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta, Trans trans)
	{
		super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
	}

	private Row fillRow(Row baserow, int startcolumn, ExcelInputRow excelInputRow) throws KettleException
	{
		debug = "fillRow start";
		Row r = new Row(baserow);

		// Keep track whether or not we handled an error for this line yet.
		boolean errorHandled = false;

		// Set values in the row...
		for (int i = startcolumn; i < excelInputRow.cells.length && i - startcolumn < r.size(); i++)
		{
			debug = "get cell #" + i;
			Cell cell = excelInputRow.cells[i];

			int rowcolumn = i - startcolumn;
			debug = "Rowcolumn = " + rowcolumn;

			Value v = r.getValue(rowcolumn);
			debug = "Value v = " + v;

			try
			{
				checkType(cell, v);
			}
			catch (KettleException ex)
			{
				if (!meta.isErrorIgnored()) throw ex;
				logBasic("Warning processing [" + debug + "] from Excel file [" + data.filename + "] : " + ex.getMessage());
				if (!errorHandled)
				{
					data.errorHandler.handleLineError(excelInputRow.rownr, excelInputRow.sheetName);
					errorHandled = true;
				}
				
				if (meta.isErrorLineSkipped())
				{
					r.setIgnore();
					return r;
				}
			}

			if (cell.getType().equals(CellType.BOOLEAN))
			{
				v.setValue(((BooleanCell) cell).getValue());
			}
			else
			{
				if (cell.getType().equals(CellType.DATE))
				{
					Date date = ((DateCell) cell).getDate();
					long time = date.getTime();
					int offset = TimeZone.getDefault().getOffset(time);
					v.setValue(new Date(time - offset));
				}
				else
				{
					if (cell.getType().equals(CellType.LABEL))
					{
						v.setValue(((LabelCell) cell).getString());
						switch (meta.getField()[rowcolumn].getTrimType())
						{
						case ExcelInputMeta.TYPE_TRIM_LEFT:
							v.ltrim();
							break;
						case ExcelInputMeta.TYPE_TRIM_RIGHT:
							v.rtrim();
							break;
						case ExcelInputMeta.TYPE_TRIM_BOTH:
							v.trim();
							break;
						default:
							break;
						}
					}
					else
					{
						if (cell.getType().equals(CellType.NUMBER))
						{
							v.setValue(((NumberCell) cell).getValue());
						}
						else
						{
							if (log.isDetailed()) logDetailed("Unknown type : " + cell.getType().toString() + " : [" + cell.getContents() + "]");
							v.setNull();
						}
					}
				}
			}

			ExcelInputField field = meta.getField()[rowcolumn];

			// Change to the appropriate type if needed...
			//
			try
			{
				if (v.getType() != field.getType())
				{
					switch (v.getType())
					{
					// Use case: we find a String: convert it using the supplied format to the desired type...
					//
					case Value.VALUE_TYPE_STRING:
						debug = "Convert string to date/number";
						switch (field.getType())
						{
						case Value.VALUE_TYPE_DATE:
							v.str2dat(field.getFormat());
							break;
						case Value.VALUE_TYPE_NUMBER:
							v.str2num(field.getFormat(), field.getDecimalSymbol(), field.getGroupSymbol(), field.getCurrencySymbol());
							break;
						default:
							v.setType(field.getType());
							break;
						}
						break;

					// Use case: we find a numeric value: convert it using the supplied format to the desired data type...
					//
					case Value.VALUE_TYPE_NUMBER:
					case Value.VALUE_TYPE_INTEGER:
						debug = "Convert number to string";
						switch (field.getType())
						{
						case Value.VALUE_TYPE_STRING:
							v.num2str(field.getFormat(), field.getDecimalSymbol(), field.getGroupSymbol(), field.getCurrencySymbol());
							break;
						case Value.VALUE_TYPE_DATE:
							v.num2str("#").str2dat(field.getFormat());
							break;
						default:
							v.setType(field.getType());
							break;
						}
						break;
					// Use case: we find a date: convert it using the supplied format to String...
					//
					case Value.VALUE_TYPE_DATE:
						debug = "Convert date to string";
						switch (field.getType())
						{
						case Value.VALUE_TYPE_STRING:
							v.dat2str(field.getFormat());
							break;
						default:
							v.setType(field.getType());
							break;
						}
						break;
					default:
						v.setType(field.getType());
					}
				}
			}
			catch (KettleException ex)
			{
				if (!meta.isErrorIgnored()) throw ex;
				logBasic("Warning processing [" + debug + "] from Excel file [" + data.filename + "] : " + ex.toString());
				if (!errorHandled) // check if we didn't log an error already for this one.
				{
					data.errorHandler.handleLineError(excelInputRow.rownr, excelInputRow.sheetName);
					errorHandled=true;
				}

				if (meta.isErrorLineSkipped())
				{
					r.setIgnore();
					return r;
				}
				else
				{
					v.setNull();
				}
			}

			// Set the meta-data of the field: length and precision
			//
			v.setLength(meta.getField()[rowcolumn].getLength(), meta.getField()[rowcolumn].getPrecision());
		}

		debug = "filename";

		// Do we need to include the filename?
		if (meta.getFileField() != null && meta.getFileField().length() > 0)
		{
			Value value = new Value(meta.getFileField(), data.filename);
			value.setLength(data.maxfilelength);
			r.addValue(value);
		}

		debug = "sheetname";

		// Do we need to include the sheetname?
		if (meta.getSheetField() != null && meta.getSheetField().length() > 0)
		{
			Value value = new Value(meta.getSheetField(), excelInputRow.sheetName);
			value.setLength(data.maxsheetlength);
			r.addValue(value);
		}

		debug = "rownumber";

		// Do we need to include the rownumber?
		if (meta.getRowNumberField() != null && meta.getRowNumberField().length() > 0)
		{
			Value value = new Value(meta.getRowNumberField(), linesWritten + 1);
			r.addValue(value);
		}

		debug = "end of fillRow";

		return r;
	}

	private void checkType(Cell cell, Value v) throws KettleException
	{
		debug="Check type of cell";
		
		if (!meta.isStrictTypes()) return;
		CellType cellType = cell.getType();
		if (cellType.equals(CellType.BOOLEAN))
		{
			if (!(v.getType() == Value.VALUE_TYPE_STRING || v.getType() == Value.VALUE_TYPE_NONE || v.getType() == Value.VALUE_TYPE_BOOLEAN))
				throw new KettleException("Invalid type Boolean, expected " + v.getTypeDesc());
		}
		else if (cellType.equals(CellType.DATE))
		{
			if (!(v.getType() == Value.VALUE_TYPE_STRING || v.getType() == Value.VALUE_TYPE_NONE || v.getType() == Value.VALUE_TYPE_DATE))
				throw new KettleException("Invalid type Date: " + cell.getContents() + ", expected " + v.getTypeDesc());
		}
		else if (cellType.equals(CellType.LABEL))
		{
			if (v.getType() == Value.VALUE_TYPE_BOOLEAN || v.getType() == Value.VALUE_TYPE_DATE || v.getType() == Value.VALUE_TYPE_INTEGER || v.getType() == Value.VALUE_TYPE_NUMBER)
				throw new KettleException("Invalid type Label: " + cell.getContents() + ", expected " + v.getTypeDesc());
		}
		else if (cellType.equals(CellType.EMPTY))
		{
			// ok
		}
		else if (cellType.equals(CellType.NUMBER))
		{
			if (!(v.getType() == Value.VALUE_TYPE_STRING || v.getType() == Value.VALUE_TYPE_NONE || v.getType() == Value.VALUE_TYPE_INTEGER || v.getType() == Value.VALUE_TYPE_BIGNUMBER || v.getType() == Value.VALUE_TYPE_NUMBER))
				throw new KettleException("Invalid type Number: " + cell.getContents() + ", expected " + v.getTypeDesc());
		}
		else
		{
			throw new KettleException("Unsupported type " + cellType + " with value: " + cell.getContents());
		}
	}

	public boolean processRow(StepMetaInterface smi, StepDataInterface sdi) throws KettleException
	{
		meta = (ExcelInputMeta) smi;
		data = (ExcelInputData) sdi;

		if (first)
		{
			first = false;
			handleMissingFiles();
		}

		// See if we're not done processing...
		// We are done processing if the filenr >= number of files.
		if (data.filenr >= data.files.nrOfFiles())
		{
			if (log.isDetailed()) logDetailed("No more files to be processes! (" + data.filenr + " files done)");
			setOutputDone(); // signal end to receiver(s)
			return false; // end of data or error.
		}

		if (meta.getRowLimit() > 0 && data.rownr > meta.getRowLimit())
		{
			// The close of the workbook is in dispose()
			if (log.isDetailed()) logDetailed("Row limit of [" + meta.getRowLimit() + "] reached: stop processing.");
			setOutputDone(); // signal end to receiver(s)
			return false; // end of data or error.
		}

		Row r = getRowFromWorkbooks();
		if (r != null)
		{
			if (!r.isIgnored())
			{
				// OK, see if we need to repeat values.
				if (data.previousRow != null)
				{
					for (int i = 0; i < meta.getField().length; i++)
					{
						Value field = r.getValue(i);
						if (field.isNull() && meta.getField()[i].isRepeated())
						{
							// Take the value from the previous row.
							Value repeat = data.previousRow.getValue(i);
							field.setValue(repeat);
						}
					}
				}

				// Remember this row for the next time around!
				data.previousRow = r;

				// Send out the good news: we found a row of data!
				putRow(r);
			}
			return true;
		}
		else
		{
			return false;
		}

	}

	private void handleMissingFiles() throws KettleException
	{
		debug = "Required files";
		List nonExistantFiles = data.files.getNonExistantFiles();

		if (nonExistantFiles.size() != 0)
		{
			String message = FileInputList.getRequiredFilesDescription(nonExistantFiles);
			log.logBasic(debug, "WARNING: Missing " + message);
			if (meta.isErrorIgnored())
				for (Iterator iter = nonExistantFiles.iterator(); iter.hasNext();)
				{
					data.errorHandler.handleNonExistantFile((File) iter.next());
				}
			else
				throw new KettleException("Following required files are missing: " + message);
		}

		List nonAccessibleFiles = data.files.getNonAccessibleFiles();
		if (nonAccessibleFiles.size() != 0)
		{
			String message = FileInputList.getRequiredFilesDescription(nonAccessibleFiles);
			log.logBasic(debug, "WARNING: Not accessible " + message);
			if (meta.isErrorIgnored())
				for (Iterator iter = nonAccessibleFiles.iterator(); iter.hasNext();)
				{
					data.errorHandler.handleNonAccessibleFile((File) iter.next());
				}
			else
				throw new KettleException("Following required files are not accessible: " + message);
		}
		debug = "End of Required files";
	}

	public Row getRowFromWorkbooks()
	{
		debug = "processRow()";
		// This procedure outputs a single Excel data row on the destination
		// rowsets...

		Row retval = new Row();
		retval.setIgnore();

		try
		{
			// First, see if a file has been opened?
			if (data.workbook == null)
			{

				// Open a new workbook..
				data.file = data.files.getFile(data.filenr);
				data.filename = data.file.getPath();
				addInterestingFile(data.file);
				debug = "open workbook #" + data.filenr + " : " + data.filename;
				if (log.isDetailed()) logDetailed("Opening workbook #" + data.filenr + " : " + data.filename);
				data.workbook = Workbook.getWorkbook(data.file);
				data.errorHandler.handleFile(data.file);
				// Start at the first sheet again...
				data.sheetnr = 0;

			}

			boolean nextsheet = false;

			// What sheet were we handling?
			debug = "Get sheet #" + data.filenr + "." + data.sheetnr;
			if (log.isDetailed()) logDetailed(debug);
			String sheetName = meta.getSheetName()[data.sheetnr];
			Sheet sheet = data.workbook.getSheet(sheetName);
			if (sheet != null)
			{
				// at what row do we continue reading?
				if (data.rownr < 0)
				{
					data.rownr = meta.getStartRow()[data.sheetnr];

					// Add an extra row if we have a header row to skip...
					if (meta.startsWithHeader())
					{
						data.rownr++;
					}

					debug = "startrow = " + data.rownr;
				}
				// Start at the specified column
				data.colnr = meta.getStartColumn()[data.sheetnr];
				debug = "startcol = " + data.colnr;

				// Build a new row and fill in the data from the sheet...
				try
				{
					Cell line[] = sheet.getRow(data.rownr);
					// Already increase cursor 1 row					
					int lineNr = ++data.rownr;
					// Excel starts counting at 0
					if (!data.filePlayList.isProcessingNeeded(data.file, lineNr, sheetName))
					{
						retval.setIgnore();
					}
					else
					{
						debug = "Get line #" + lineNr + " from sheet #" + data.filenr + "." + data.sheetnr;
						if (log.isRowLevel()) logRowlevel(debug);

						if (log.isRowLevel()) logRowlevel("Read line with " + line.length + " cells");
						ExcelInputRow excelInputRow = new ExcelInputRow(sheet.getName(), lineNr, line);
						Row r = fillRow(data.row, data.colnr, excelInputRow);
						if (log.isRowLevel()) logRowlevel("Converted line to row #" + lineNr + " : " + r);

						if (line.length > 0 || !meta.ignoreEmptyRows())
						{
							// Put the row
							retval = r;
						}

						if (line.length == 0 && meta.stopOnEmpty())
						{
							nextsheet = true;
						}
					}
				}
				catch (ArrayIndexOutOfBoundsException e)
				{
					if (log.isRowLevel()) logRowlevel("Out of index error: move to next sheet! (" + debug + ")");
					// We tried to read below the last line in the sheet.
					// Go to the next sheet...
					nextsheet = true;
				}
			}
			else
			{
				nextsheet = true;
			}

			if (nextsheet)
			{
				// Go to the next sheet
				data.sheetnr++;

				// Reset the start-row:
				data.rownr = -1;

				// no previous row yet, don't take it from the previous sheet!
				// (that whould be plain wrong!)
				data.previousRow = null;

				// Perhaps it was the last sheet?
				if (data.sheetnr >= meta.getSheetName().length)
				{
					jumpToNextFile();
				}
			}
		}
		catch (Exception e)
		{
			logError("Error processing row in [" + debug + "] from Excel file [" + data.filename + "] : " + e.toString());
			setErrors(1);
			stopAll();
			return null;
		}

		return retval;
	}

	private void jumpToNextFile() throws KettleException
	{
		data.sheetnr = 0;

		// Reset the start-row:
		data.rownr = -1;

		// no previous row yet, don't take it from the previous sheet! (that
		// whould be plain wrong!)
		data.previousRow = null;

		// Close the workbook!
		data.workbook.close();
		data.workbook = null; // marker to open again.
		data.errorHandler.close();

		// advance to the next file!
		data.filenr++;
	}

	private void initErrorHandling()
	{
		List errorHandlers = new ArrayList(2);
		if (meta.getLineNumberFilesDestinationDirectory() != null)
			errorHandlers.add(new FileErrorHandlerContentLineNumber(getTrans().getCurrentDate(), meta.getLineNumberFilesDestinationDirectory(), meta.getLineNumberFilesExtension(), "Latin1", this));
		if (meta.getErrorFilesDestinationDirectory() != null)
			errorHandlers.add(new FileErrorHandlerMissingFiles(getTrans().getCurrentDate(), meta.getErrorFilesDestinationDirectory(), meta.getErrorFilesExtension(), "Latin1", this));
		data.errorHandler = new CompositeFileErrorHandler(errorHandlers);
	}

	private void initReplayFactory()
	{
		Date replayDate = getTrans().getReplayDate();
		if (replayDate == null)
			data.filePlayList = FilePlayListAll.INSTANCE;
		else
			data.filePlayList = new FilePlayListReplay(replayDate, meta.getLineNumberFilesDestinationDirectory(), meta.getLineNumberFilesExtension(), meta.getErrorFilesDestinationDirectory(), meta
					.getErrorFilesExtension(), "Latin1");
	}

	public boolean init(StepMetaInterface smi, StepDataInterface sdi)
	{
		meta = (ExcelInputMeta) smi;
		data = (ExcelInputData) sdi;

		if (super.init(smi, sdi))
		{
			initErrorHandling();
			initReplayFactory();
			data.files = meta.getFileList();
			if (data.files.nrOfFiles() == 0 && data.files.nrOfMissingFiles() == 0)
			{
				logError("No file(s) specified! Stop processing.");
				return false;
			}

			data.row = meta.getEmptyFields();
			if (data.row.size() > 0)
			{
				// Determine the maximum filename length...
				data.maxfilelength = -1;

				for (Iterator iter = data.files.getFiles().iterator(); iter.hasNext();)
				{
					File file = (File) iter.next();
					String name = file.getName();
					if (name.length() > data.maxfilelength) data.maxfilelength = name.length();
				}

				// Determine the maximum sheetname length...
				data.maxsheetlength = -1;
				for (int i = 0; i < meta.getSheetName().length; i++)
					if (meta.getSheetName()[i].length() > data.maxsheetlength) data.maxsheetlength = meta.getSheetName()[i].length();

				return true;
			}
			else
			{
				logError("No input fields defined!");
			}

		}
		return false;
	}

	public void dispose(StepMetaInterface smi, StepDataInterface sdi)
	{
		meta = (ExcelInputMeta) smi;
		data = (ExcelInputData) sdi;

		if (data.workbook != null) data.workbook.close();
		try
		{
			data.errorHandler.close();
		}
		catch (KettleException e)
		{
			if (log.isDebug()) logDebug("Could not close errorHandler");
		}

		super.dispose(smi, sdi);
	}

	public void run()
	{
		try
		{
			logBasic("Starting to run...");
			while (processRow(meta, data) && !isStopped())
				;
		}
		catch (Exception e)
		{
			logError("Unexpected error in '" + debug + "' : " + e.toString());
			setErrors(1);
			stopAll();
		}
		finally
		{
			dispose(meta, data);
			logSummary();
			markStop();
		}
	}
}
